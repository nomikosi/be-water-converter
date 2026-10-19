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

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates TypeScript interfaces from a JSON structure.
 *
 * <p>An interface describes the JSON as it is, so every property keeps its key:
 * one that is no identifier is quoted rather than renamed, and nothing maps
 * names at run time. A key the example showed absent or null in some element
 * is optional and nullable; a value seen only as null is {@code unknown}.
 */
public class TypeScriptGenerator {

    /** Name of the generated root interface. */
    public static final String ROOT_TYPE_NAME = "Root";

    /**
     * Global types an interface of the same name would shadow in its module:
     * {@code Record} is one the output itself names, and the rest are ones
     * code beside it is likely to.
     */
    static final Set<String> RESERVED_TYPE_NAMES = Set.of(
          "Array", "BigInt", "Boolean", "Date", "Error", "Function", "JSON", "Map", "Math", "Number",
          "Object", "Omit", "Partial", "Pick", "Promise", "Readonly", "Record", "Required", "Set",
          "String", "Symbol");

    /** A property name that needs no quotes. Reserved words are allowed as property names. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    public String fromJson(String json) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        StructureModel model = StructureModel.from(GeneratorJson.readTree(json), ROOT_TYPE_NAME,
              SourceConventions::typeNameFromWords, RESERVED_TYPE_NAMES);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, JsonNode> type : model.types().entrySet()) {
            if (!out.isEmpty()) out.append('\n');
            declare(type.getKey(), type.getValue(), model, out);
        }
        return out.toString();
    }

    private static void declare(String name, JsonNode object, StructureModel model, StringBuilder out) {
        // Only the root can be empty here: an empty object below it is a record.
        // An empty interface would accept any value but null.
        if (object.isEmpty()) {
            out.append("export type ").append(name).append(" = Record<string, never>;\n");
            return;
        }
        out.append("export interface ").append(name).append(" {\n");
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String type = typeOf(property.getValue(), model);
            boolean optional = model.isOptional(object, property.getKey());
            if (optional && !type.equals("unknown")) type += " | null";
            out.append("  ").append(propertyName(property.getKey())).append(optional ? "?: " : ": ")
                  .append(type).append(";\n");
        }
        out.append("}\n");
    }

    private static String typeOf(JsonNode node, StructureModel model) {
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isTextual()) return "string";
        if (node.isObject()) {
            String name = model.nameOf(node);
            return name != null ? name : "Record<string, unknown>";
        }
        if (node.isArray()) {
            JsonNode element = model.elementOf(node);
            if (element == null) return "unknown[]";
            String type = typeOf(element, model);
            if (type.equals("unknown")) return "unknown[]";
            if (model.hasNullElement(node)) return "(" + type + " | null)[]";
            return type + "[]";
        }
        // Null, or elements of different kinds: the example does not say.
        return "unknown";
    }

    private static String propertyName(String key) {
        return IDENTIFIER.matcher(key).matches() ? key : stringLiteral(key);
    }

    /**
     * A double-quoted literal, escaped as JSON escapes it, which TypeScript
     * reads alike. Half a surrogate pair is escaped too: written raw, no file
     * encoding could hold it.
     */
    static String stringLiteral(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean lone = Character.isHighSurrogate(c)
                          ? i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))
                          : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(value.charAt(i - 1)));
                    if (c < 0x20 || c == 0x7F || lone) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
