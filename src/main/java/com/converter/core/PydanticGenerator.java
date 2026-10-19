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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates Pydantic v2 models from a JSON structure.
 *
 * <p>Fields are snake_case, and {@code Field(alias=...)} keeps the key of every
 * field whose name differs from it. A field reads only its key, never one that
 * happens to match its Python name ({@code populate_by_name} would let a
 * {@code value} aliased to {@code Value} read a {@code value} key beside it).
 * A key the example showed absent or null in some element is
 * {@code X | None = None}; a value seen only as null, or elements of different
 * kinds, is {@code Any}. Models come before the models that use them, so no
 * annotation is a forward reference. Writing a model back as its JSON takes
 * {@code model_dump_json(by_alias=True, exclude_unset=True)}.
 *
 * <p>Annotations use {@code X | None} and {@code list[X]}: Python 3.10 or later.
 */
public class PydanticGenerator {

    /** Name of the generated root model. */
    public static final String ROOT_TYPE_NAME = "Root";

    /** Names the output imports, which a model of the same name would replace. */
    static final Set<String> RESERVED_TYPE_NAMES = Set.of(
          "Any", "BaseModel", "ConfigDict", "Field", "Optional");

    /**
     * Names no field may take: Python's keywords; the types annotations name,
     * which a field of that name would hide from the annotations after it in
     * the class body; and what BaseModel itself defines.
     */
    static final Set<String> RESERVED_FIELD_NAMES = Set.of(
          "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
          "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
          "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
          "bool", "date", "datetime", "dict", "float", "int", "list", "str",
          "construct", "copy", "from_orm", "json", "parse_file", "parse_obj", "parse_raw", "schema",
          "schema_json", "update_forward_refs", "validate", "model_computed_fields", "model_config",
          "model_construct", "model_copy", "model_dump", "model_dump_json", "model_extra",
          "model_fields", "model_fields_set", "model_json_schema", "model_parametrized_name",
          "model_post_init", "model_rebuild", "model_validate", "model_validate_json",
          "model_validate_strings");

    public String fromJson(String json) throws Exception {
        return fromJson(json, true);
    }

    /**
     * @param detectDates when true, ISO-8601 strings are typed as {@code date}
     *                    or {@code datetime} instead of {@code str}
     */
    public String fromJson(String json, boolean detectDates) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        StructureModel model = StructureModel.from(GeneratorJson.readTree(json), ROOT_TYPE_NAME,
              SourceConventions::typeNameFromWords, RESERVED_TYPE_NAMES);
        Set<String> imports = new LinkedHashSet<>();
        List<String> classes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> type : model.types().entrySet())
            classes.add(declare(type.getKey(), type.getValue(), model, detectDates, imports));
        // Discovery puts a model before those inside it; Python needs them after.
        Collections.reverse(classes);

        StringBuilder out = new StringBuilder();
        List<String> datetime = new ArrayList<>();
        if (imports.contains("date")) datetime.add("date");
        if (imports.contains("datetime")) datetime.add("datetime");
        if (!datetime.isEmpty()) out.append("from datetime import ").append(String.join(", ", datetime)).append('\n');
        if (imports.contains("Any")) out.append("from typing import Any\n");
        if (!out.isEmpty()) out.append('\n');
        List<String> pydantic = new ArrayList<>(List.of("BaseModel"));
        if (imports.contains("ConfigDict")) pydantic.add("ConfigDict");
        if (imports.contains("Field")) pydantic.add("Field");
        out.append("from pydantic import ").append(String.join(", ", pydantic)).append('\n');
        for (String declared : classes) out.append("\n\n").append(declared);
        return out.toString();
    }

    private static String declare(String name, JsonNode object, StructureModel model, boolean detectDates,
          Set<String> imports) {
        StringBuilder fields = new StringBuilder();
        Set<String> used = new LinkedHashSet<>();
        boolean modelPrefix = false;
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String key = property.getKey();
            String field = SourceConventions.uniqueName(fieldName(key), "_", used);
            String type = typeOf(property.getValue(), model, detectDates, imports);
            boolean optional = model.isOptional(object, key);
            if (optional && !type.equals("Any")) type += " | None";
            fields.append("    ").append(field).append(": ").append(type);
            if (!field.equals(key)) {
                imports.add("Field");
                fields.append(" = Field(").append(optional ? "default=None, " : "")
                      .append("alias=").append(stringLiteral(key)).append(')');
            } else if (optional) {
                fields.append(" = None");
            }
            fields.append('\n');
            modelPrefix |= field.startsWith("model_");
        }
        StringBuilder out = new StringBuilder("class ").append(name).append("(BaseModel):\n");
        // Pydantic before 2.10 warns about a field named model_*, a prefix it kept for itself.
        if (modelPrefix) {
            imports.add("ConfigDict");
            out.append("    model_config = ConfigDict(protected_namespaces=())\n\n");
        }
        if (fields.isEmpty()) out.append("    pass\n");
        return out.append(fields).toString();
    }

    /** {@code first_name}; a reserved name takes an underscore after it, as {@code class_}. */
    static String fieldName(String key) {
        String name = SourceConventions.snakeCase(key);
        if (name.isEmpty()) return "value";
        // Not an underscore before a digit: Pydantic takes a leading one for a private attribute.
        if (Character.isDigit(name.charAt(0))) return "field_" + name;
        return RESERVED_FIELD_NAMES.contains(name) ? name + "_" : name;
    }

    private static String typeOf(JsonNode node, StructureModel model, boolean detectDates, Set<String> imports) {
        if (node.isIntegralNumber()) return "int";
        if (node.isNumber()) return "float";
        if (node.isBoolean()) return "bool";
        if (node.isTextual()) {
            if (detectDates) {
                String temporal = SourceConventions.temporalTypeFor(node.asText());
                if (temporal != null) {
                    String type = temporal.equals("LocalDate") ? "date" : "datetime";
                    imports.add(type);
                    return type;
                }
            }
            return "str";
        }
        if (node.isObject()) {
            String name = model.nameOf(node);
            if (name != null) return name;
            imports.add("Any");
            return "dict[str, Any]";
        }
        if (node.isArray()) {
            JsonNode element = model.elementOf(node);
            String type = element == null ? "Any" : typeOf(element, model, detectDates, imports);
            if (type.equals("Any")) imports.add("Any");
            else if (model.hasNullElement(node)) type += " | None";
            return "list[" + type + "]";
        }
        imports.add("Any");
        return "Any";
    }

    /**
     * A double-quoted Python literal. A string there holds code points, so a
     * character beyond the BMP is written as itself: as two {@code \}{@code u}
     * escapes it would be two lone surrogates. Half of a pair is escaped.
     */
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
                    if (c < 0x20 || c == 0x7F || c >= 0xD800 && c <= 0xDFFF)
                        out.append(String.format(Locale.ROOT, "\\u%04x", c));
                    else out.appendCodePoint(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
