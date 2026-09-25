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

import static com.converter.core.SourceConventions.temporalTypeFor;
import static com.converter.core.SourceConventions.capitalize;
import static com.converter.core.SourceConventions.uniqueName;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Deserialising the output needs {@code jackson-module-kotlin}: a data class
 * has no no-argument constructor, and the emitted {@code @JsonProperty} carries
 * no use-site target, so Kotlin applies it to the constructor parameter — which
 * only that module reads. This is documented rather than worked around because
 * no annotation placement makes plain {@code jackson-databind} construct a data
 * class.
 */
public class KotlinDataClassGenerator {

    /** Name of the generated root class. */
    public static final String ROOT_CLASS_NAME = "Root";


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

    /**
     * Every type name this generator can emit: the simple names of the imports
     * in {@link #generate} plus the types {@link #resolveType} returns. A
     * generated class may take none of them — {@code data class List} shadows
     * the auto-imported {@code kotlin.collections.List}, so every
     * {@code List<…>} in the same file then fails to resolve.
     */
    static final Set<String> RESERVED_TYPE_NAMES = Set.of(
          "JsonProperty",
          "BigDecimal", "BigInteger",
          "LocalDate", "LocalDateTime", "OffsetDateTime",
          "Any", "Boolean", "Double", "Float", "Int", "List", "Long", "String");

    /** Kotlin identifiers, unlike Java's, do not admit {@code $}. */
    private static final Pattern ILLEGAL_IN_IDENTIFIER = Pattern.compile("[^a-zA-Z0-9_]");

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
        // A root array is unwrapped by StructureModel to the merged shape of
        // its elements, the same rule it applies to a nested array under a key.
        return generate(GeneratorJson.readTree(json), detectDates);
    }

    // ── Generation ────────────────────────────────────────────────────────

    private String generate(JsonNode root, boolean detectDates) {
        StructureModel model = StructureModel.from(root, ROOT_CLASS_NAME,
              key -> capitalize(toCamelCase(key)), RESERVED_TYPE_NAMES);

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

        List<String> propertyTypes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String type = resolveType(entry.getValue(), entry.getKey(), detectDates, usedTypes, model);
            if (model.isOptional(node, entry.getKey()) && !type.endsWith("?")) type += "?";
            propertyTypes.add(type);
        }
        validateJvmSlots(className, propertyTypes);
        sb.append("data class ").append(className).append("(\n");
        Set<String> usedNames = new LinkedHashSet<>();
        int remaining = node.size();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String originalKey = e.getKey();
            String propertyName = uniqueName(toCamelCase(originalKey), "", usedNames);
            String type = propertyTypes.get(node.size() - remaining);

            if (!propertyName.equals(originalKey)) {
                if (SourceConventions.isMappableKey(originalKey)) {
                    usedTypes.add("JsonProperty");
                    sb.append("    @JsonProperty(\"")
                          .append(SourceConventions.kotlinStringLiteral(originalKey))
                          .append("\")\n");
                } else {
                    sb.append("    // ").append(SourceConventions.UNMAPPABLE_KEY_NOTE).append("\n");
                }
            }
            sb.append("    val ").append(propertyName).append(": ").append(type);
            if (--remaining > 0) sb.append(',');
            sb.append('\n');
        }
        sb.append(")\n");
    }

    private static void validateJvmSlots(String className, List<String> types) {
        int slots = 0;
        for (String type : types) slots += type.equals("Long") || type.equals("Double") ? 2 : 1;
        // copy$default takes the receiver, the properties, one mask per 32
        // properties, and a marker. Nullable primitives are boxed (one slot).
        int copySlots = slots + (types.size() + 31) / 32 + 2;
        if (copySlots > 255)
            throw new IllegalArgumentException("Kotlin class " + className + " needs " + copySlots
                  + " JVM parameter slots in its generated copy method (limit 255). "
                  + "Split this object into smaller nested objects before generating Kotlin.");
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
            // The merged shape of every element, matching JavaPojoGenerator; a
            // null among them makes the element type nullable.
            JsonNode element = model.elementOf(node);
            boolean nullable = model.hasNullElement(node);
            if (element == null) return nullable ? "List<Any?>" : "List<Any>";
            String elementType = resolveType(element, fieldName, detectDates, usedTypes, model);
            if (nullable && !elementType.endsWith("?")) elementType += "?";
            return "List<" + elementType + ">";
        }
        return "Any";
    }

    /**
     * Keywords are suffixed rather than back-quoted: the {@code @JsonProperty}
     * that the rename triggers is what preserves the original key, and
     * {@code val `class`: X} reads badly in generated code.
     */
    private String toCamelCase(String s) {
        return SourceConventions.toCamelCase(s, ILLEGAL_IN_IDENTIFIER, KOTLIN_KEYWORDS);
    }
}
