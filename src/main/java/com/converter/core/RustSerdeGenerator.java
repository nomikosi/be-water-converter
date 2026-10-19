/*
 * Copyright (c) 2026 Nomikosi Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.converter.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates Rust structs that serde reads and writes, from a JSON structure.
 *
 * <p>Fields are snake_case, and {@code #[serde(rename)]} keeps the key of every
 * field whose name differs from it. A key the example showed absent or null in
 * some element is an {@code Option}, left out when it is {@code None}; a value
 * seen only as null, or elements of different kinds, a {@code serde_json::Value}.
 *
 * <p>Numbers are {@code i64} and {@code f64}, and {@code u64} for a whole number
 * past {@code i64} that still fits one. A number neither holds exactly is an
 * {@code f64}, which rounds it: serde_json reads exact big numbers only with its
 * {@code arbitrary_precision} feature.
 */
public class RustSerdeGenerator {

    /** Name of the generated root struct. */
    public static final String ROOT_TYPE_NAME = "Root";

    /** Names from the prelude and the imports that a struct of the same name would shadow. */
    static final Set<String> RESERVED_TYPE_NAMES = Set.of(
          "Box", "Deserialize", "Err", "Map", "None", "Number", "Ok", "Option", "Result", "Self",
          "Serialize", "Some", "String", "Value", "Vec");

    /** Strict and reserved keywords, which no field may be named; raw identifiers cannot take them all. */
    static final Set<String> KEYWORDS = Set.of(
          "abstract", "as", "async", "await", "become", "box", "break", "const", "continue", "crate",
          "do", "dyn", "else", "enum", "extern", "false", "final", "fn", "for", "gen", "if", "impl",
          "in", "let", "loop", "macro", "match", "mod", "move", "mut", "override", "priv", "pub",
          "ref", "return", "self", "static", "struct", "super", "trait", "true", "try", "type",
          "typeof", "unsafe", "unsized", "use", "virtual", "where", "while", "yield");

    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static final String DERIVES = "#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]\n";

    public String fromJson(String json) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        StructureModel model = StructureModel.from(GeneratorJson.readTree(json), ROOT_TYPE_NAME,
              SourceConventions::typeNameFromWords, RESERVED_TYPE_NAMES);
        StringBuilder out = new StringBuilder("use serde::{Deserialize, Serialize};\n");
        for (Map.Entry<String, JsonNode> type : model.types().entrySet()) {
            out.append('\n');
            declare(type.getKey(), type.getValue(), model, out);
        }
        return out.toString();
    }

    private static void declare(String name, JsonNode object, StructureModel model, StringBuilder out) {
        out.append(DERIVES).append("pub struct ").append(name);
        if (object.isEmpty()) {
            out.append(" {}\n");
            return;
        }
        out.append(" {\n");
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String key = property.getKey();
            String field = SourceConventions.uniqueName(fieldName(key), "_", used);
            String type = typeOf(property.getValue(), model);
            boolean optional = model.isOptional(object, key);
            StringBuilder attributes = new StringBuilder();
            if (!field.equals(key)) attributes.append("rename = ").append(stringLiteral(key));
            if (optional) {
                if (!attributes.isEmpty()) attributes.append(", ");
                if (type.equals("serde_json::Value")) {
                    // Value holds null itself; missing reads as null, and null is left out.
                    attributes.append("default, skip_serializing_if = \"serde_json::Value::is_null\"");
                } else {
                    type = "Option<" + type + ">";
                    attributes.append("skip_serializing_if = \"Option::is_none\"");
                }
            }
            if (!attributes.isEmpty()) out.append("    #[serde(").append(attributes).append(")]\n");
            out.append("    pub ").append(field).append(": ").append(type).append(",\n");
        }
        out.append("}\n");
    }

    /** {@code first_name}: snake_case, a keyword taking an underscore after it, as {@code type_}. */
    static String fieldName(String key) {
        String name = SourceConventions.snakeCase(key);
        if (name.isEmpty()) return "value";
        if (Character.isDigit(name.charAt(0))) return "field_" + name;
        return KEYWORDS.contains(name) ? name + "_" : name;
    }

    private static String typeOf(JsonNode node, StructureModel model) {
        if (node.isInt() || node.isShort() || node.isLong()) return "i64";
        if (node.isBigInteger()) {
            BigInteger value = node.bigIntegerValue();
            return value.signum() >= 0 && value.compareTo(U64_MAX) <= 0 ? "u64" : "f64";
        }
        if (node.isNumber()) return "f64";
        if (node.isBoolean()) return "bool";
        if (node.isTextual()) return "String";
        if (node.isObject()) {
            String name = model.nameOf(node);
            return name != null ? name : "serde_json::Map<String, serde_json::Value>";
        }
        if (node.isArray()) {
            JsonNode element = model.elementOf(node);
            if (element == null) return "Vec<serde_json::Value>";
            String type = typeOf(element, model);
            if (model.hasNullElement(node) && !type.equals("serde_json::Value")) type = "Option<" + type + ">";
            return "Vec<" + type + ">";
        }
        return "serde_json::Value";
    }

    /** A Rust string literal, escaping what a line cannot hold the way Rust does: hex in braces. */
    static String stringLiteral(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); ) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Half a surrogate pair is no char at all: Rust has no way to write one.
                    if (c >= 0xD800 && c <= 0xDFFF)
                        throw new IllegalArgumentException("The key " + TypeScriptGenerator.stringLiteral(value)
                              + " holds half of a surrogate pair, which a Rust string cannot.");
                    if (c < 0x20 || c == 0x7F) out.append(String.format(Locale.ROOT, "\\u{%x}", c));
                    else out.appendCodePoint(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
