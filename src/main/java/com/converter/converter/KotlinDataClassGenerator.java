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

package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates Kotlin data classes from a JSON structure.
 *
 * <p>Structure discovery is shared with {@link JavaPojoGenerator} through
 * {@link StructureModel}; only naming, typing and emission differ. Kotlin allows
 * several top-level declarations per file, so unlike the Java output every class
 * here is public and the whole block pastes into one {@code .kt} file.
 *
 * <p>Types are non-null except where the example showed {@code null}, because an
 * example can only demonstrate what was present. Values that appeared as null are
 * typed {@code Any?}.
 */
public class KotlinDataClassGenerator {

    /** Name of the generated root class. */
    public static final String ROOT_CLASS_NAME = "Root";

    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Kotlin hard keywords, which cannot be identifiers at all. Soft and modifier
     * keywords (data, value, expect, ...) are legal as property names and are
     * deliberately not listed.
     */
    private static final Set<String> KOTLIN_KEYWORDS = Set.of(
          "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
          "if", "in", "interface", "is", "null", "object", "package", "return",
          "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
          "var", "when", "while");

    private static final Pattern ISO_DATE =
          Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ISO_DATETIME_OFFSET =
          Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})");
    private static final Pattern ISO_DATETIME_LOCAL =
          Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");

    public String fromJson(String json) throws Exception {
        return fromJson(json, true);
    }

    /**
     * @param detectDates when true, ISO-8601 strings are typed as
     *                    {@code LocalDate} / {@code LocalDateTime} /
     *                    {@code OffsetDateTime} instead of {@code String}.
     */
    public String fromJson(String json, boolean detectDates) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        JsonNode root = jsonMapper.readTree(json);
        if (root.isArray()) {
            if (root.isEmpty())
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            root = root.get(0);
        }
        return generate(root, detectDates);
    }

    // ── Generation ────────────────────────────────────────────────────────

    private String generate(JsonNode root, boolean detectDates) {
        StructureModel model = StructureModel.from(root, ROOT_CLASS_NAME,
              key -> capitalize(toCamelCase(key)));

        Set<String> usedTypes = new LinkedHashSet<>();
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, JsonNode> entry : model.types().entrySet()) {
            generateClass(entry.getKey(), entry.getValue(), body, detectDates, usedTypes, model);
            body.append("\n");
        }

        StringBuilder out = new StringBuilder();
        if (usedTypes.contains("JsonProperty"))
            out.append("import com.fasterxml.jackson.annotation.JsonProperty\n");
        if (usedTypes.contains("BigDecimal"))     out.append("import java.math.BigDecimal\n");
        if (usedTypes.contains("BigInteger"))     out.append("import java.math.BigInteger\n");
        if (usedTypes.contains("LocalDate"))      out.append("import java.time.LocalDate\n");
        if (usedTypes.contains("LocalDateTime"))  out.append("import java.time.LocalDateTime\n");
        if (usedTypes.contains("OffsetDateTime")) out.append("import java.time.OffsetDateTime\n");
        if (!out.isEmpty()) out.append("\n");
        return out.append(body).toString();
    }

    private void generateClass(String className, JsonNode node, StringBuilder sb,
          boolean detectDates, Set<String> usedTypes, StructureModel model) {
        // A data class must declare at least one parameter, so an object with no
        // properties has to be emitted as a plain class rather than a data class.
        if (node.isEmpty()) {
            sb.append("class ").append(className).append("\n");
            return;
        }

        sb.append("data class ").append(className).append("(\n");
        Set<String> usedNames = new LinkedHashSet<>();
        int remaining = node.size();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String originalKey = e.getKey();
            String propertyName = uniqueName(toCamelCase(originalKey), usedNames);
            String type = resolveType(e.getValue(), originalKey, detectDates, usedTypes, model);

            if (!propertyName.equals(originalKey)) {
                usedTypes.add("JsonProperty");
                sb.append("    @JsonProperty(\"").append(escape(originalKey)).append("\")\n");
            }
            sb.append("    val ").append(propertyName).append(": ").append(type);
            if (--remaining > 0) sb.append(',');
            sb.append('\n');
        }
        sb.append(")\n");
    }

    private String uniqueName(String name, Set<String> used) {
        if (used.add(name)) return name;
        int n = 2;
        while (!used.add(name + n)) n++;
        return name + n;
    }

    private String resolveType(JsonNode node, String fieldName, boolean detectDates,
          Set<String> usedTypes, StructureModel model) {
        if (node.isInt() || node.isShort())  return "Int";
        if (node.isLong())                   return "Long";
        if (node.isBigInteger())             { usedTypes.add("BigInteger"); return "BigInteger"; }
        if (node.isFloat())                  return "Float";
        if (node.isDouble())                 return "Double";
        if (node.isBigDecimal())             { usedTypes.add("BigDecimal"); return "BigDecimal"; }
        if (node.isBoolean())                return "Boolean";
        if (node.isTextual()) {
            if (detectDates) {
                String temporal = temporalTypeFor(node.asText());
                if (temporal != null) {
                    usedTypes.add(temporal);
                    return temporal;
                }
            }
            return "String";
        }
        // The example showed null, so the type is genuinely unknown AND nullable.
        if (node.isNull())                   return "Any?";
        if (node.isObject()) {
            String assigned = model.nameOf(node);
            return assigned != null ? assigned : capitalize(toCamelCase(fieldName));
        }
        if (node.isArray()) {
            if (node.isEmpty()) return "List<Any>";
            // Typed from element 0, matching JavaPojoGenerator. JsonSchemaGenerator
            // deliberately takes the other position (anyOf) for the same input.
            return "List<" + resolveType(node.get(0), fieldName, detectDates, usedTypes, model) + ">";
        }
        return "Any";
    }

    /** Confirms an ISO-8601 match with a real parse, so "2025-13-99" stays a String. */
    private String temporalTypeFor(String value) {
        try {
            if (ISO_DATETIME_OFFSET.matcher(value).matches()) {
                java.time.OffsetDateTime.parse(value);
                return "OffsetDateTime";
            }
            if (ISO_DATETIME_LOCAL.matcher(value).matches()) {
                java.time.LocalDateTime.parse(value);
                return "LocalDateTime";
            }
            if (ISO_DATE.matcher(value).matches()) {
                java.time.LocalDate.parse(value);
                return "LocalDate";
            }
        } catch (java.time.format.DateTimeParseException notADate) {
            return null;
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return "_";
        String[] parts = s.split("[_\\-.]+");
        StringBuilder sb = new StringBuilder();
        for (String rawPart : parts) {
            if (rawPart.isEmpty()) continue;
            String part = sanitize(rawPart);
            if (part.isEmpty()) continue;
            if (sb.isEmpty()) {
                sb.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (sb.isEmpty()) sb.append('_');
        String result = sb.toString();
        if (Character.isDigit(result.charAt(0))) result = "_" + result;
        // Suffixed rather than back-quoted: the @JsonProperty that the rename
        // triggers is what preserves the original key, and `val \`class\`: X`
        // reads badly in generated code.
        if (KOTLIN_KEYWORDS.contains(result)) result = result + "Value";
        return result;
    }

    private String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9_]", "_"); }
}
