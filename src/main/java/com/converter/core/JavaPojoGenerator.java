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

import java.util.*;
import java.util.regex.Pattern;

/**
 * Generates Java POJO class skeletons from a JSON structure; other formats
 * reach it through the JSON pivot.
 * Each class contains only field declarations (with @JsonProperty where the
 * JSON key differs from the camelCase Java name). Constructors and accessors
 * are not emitted; enable Lombok mode to annotate the generated classes with
 * @Data, @NoArgsConstructor, and @AllArgsConstructor instead.
 */
public class JavaPojoGenerator {

    /** Name of the generated root class; the only public type in the output. */
    public static final String ROOT_CLASS_NAME = "Root";

    private static final Set<String> JAVA_KEYWORDS = Set.of(
          "abstract", "assert", "boolean", "break", "byte", "case", "catch",
          "char", "class", "const", "continue", "default", "do", "double",
          "else", "enum", "extends", "final", "finally", "float", "for",
          "goto", "if", "implements", "import", "instanceof", "int",
          "interface", "long", "native", "new", "package", "private",
          "protected", "public", "return", "short", "static", "strictfp",
          "super", "switch", "synchronized", "this", "throw", "throws",
          "transient", "try", "void", "volatile", "while",
          "var", "yield", "record", "sealed", "permits",
          // Not keywords but literals, and JLS 3.8 bars them from identifiers
          // just the same: "private Integer false;" does not parse.
          "true", "false", "null");

    /**
     * Every type name this generator can emit: the simple names of the imports
     * in {@link #generate} plus the {@code java.lang} types
     * {@link #resolveJavaType} returns. A generated class may take none of
     * them — {@code class List} beside {@code import java.util.List} is
     * "already defined in this compilation unit", and {@code class String}
     * compiles but silently shadows {@code java.lang.String} for every field
     * in the file.
     *
     * <p>Conservative on purpose: names are assigned before generation knows
     * which imports it will actually emit, so {@code List} is reserved even in a
     * document with no array. Reserving too much costs a suffix; reserving too
     * little costs a file that does not compile.
     */
    private static final Set<String> RESERVED_TYPE_NAMES = Set.of(
          "JsonAutoDetect", "JsonProperty",
          "BigDecimal", "BigInteger",
          "LocalDate", "LocalDateTime", "OffsetDateTime",
          "List",
          "Boolean", "Double", "Float", "Integer", "Long", "Object", "String");

    /**
     * The Lombok annotations, reserved only in Lombok mode. {@code data} is a
     * common key, and reserving {@code Data} unconditionally renamed its class
     * for every document — including the ones that emit no Lombok import at all,
     * and where the Kotlin generator still emits {@code Data}.
     */
    private static final Set<String> LOMBOK_TYPE_NAMES =
          Set.of("AllArgsConstructor", "Data", "NoArgsConstructor");

    private static Set<String> reservedTypeNames(boolean useLombok) {
        if (!useLombok) return RESERVED_TYPE_NAMES;
        Set<String> all = new HashSet<>(RESERVED_TYPE_NAMES);
        all.addAll(LOMBOK_TYPE_NAMES);
        return all;
    }

    /**
     * Java identifiers admit {@code $}, but generated ones do not use it:
     * Lombok skips a field whose name starts with {@code $}, so a class whose
     * only key was {@code $ref} got a second no-argument constructor from
     * {@code @AllArgsConstructor} and did not compile. {@code $ref} becomes
     * {@code _ref}, mapped back by {@code @JsonProperty}.
     */
    private static final Pattern ILLEGAL_IN_IDENTIFIER = Pattern.compile("[^a-zA-Z0-9_]");

    public String fromJson(String json) throws Exception {
        return fromJson(json, false);
    }

    public String fromJson(String json, boolean useLombok) throws Exception {
        return fromJson(json, useLombok, true);
    }

    /**
     * @param detectDates when true, textual values in ISO-8601 form are typed
     *                    as {@code LocalDate}, {@code LocalDateTime} or
     *                    {@code OffsetDateTime} instead of {@code String}.
     */
    public String fromJson(String json, boolean useLombok, boolean detectDates) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        // A root array is unwrapped by StructureModel to the merged shape of
        // its elements, the same rule it applies to a nested array under a key.
        return generate(GeneratorJson.readTree(json), "Root", useLombok, detectDates);
    }

    // ── Internal generation ───────────────────────────────────────────────

    private String generate(JsonNode root, String rootClassName, boolean useLombok,
          boolean detectDates) {
        StructureModel model = StructureModel.from(root, rootClassName,
              key -> capitalize(toCamelCase(key)), reservedTypeNames(useLombok));

        Set<String> usedTypes = new HashSet<>();
        StringBuilder body = new StringBuilder();
        boolean isFirst = true;
        for (Map.Entry<String, JsonNode> entry : model.types().entrySet()) {
            generateClass(entry.getKey(), entry.getValue(), body, useLombok,
                  detectDates, usedTypes, model, isFirst);
            body.append("\n");
            isFirst = false;
        }

        StringBuilder sb = new StringBuilder();
        if (usedTypes.contains("JsonAutoDetect"))
            sb.append("import com.fasterxml.jackson.annotation.JsonAutoDetect;\n");
        if (usedTypes.contains("JsonProperty"))
            sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        if (usedTypes.contains("BigDecimal"))     sb.append("import java.math.BigDecimal;\n");
        if (usedTypes.contains("BigInteger"))     sb.append("import java.math.BigInteger;\n");
        if (usedTypes.contains("LocalDate"))      sb.append("import java.time.LocalDate;\n");
        if (usedTypes.contains("LocalDateTime"))  sb.append("import java.time.LocalDateTime;\n");
        if (usedTypes.contains("OffsetDateTime")) sb.append("import java.time.OffsetDateTime;\n");
        if (usedTypes.contains("List"))           sb.append("import java.util.List;\n");
        if (useLombok) {
            sb.append("import lombok.AllArgsConstructor;\n");
            sb.append("import lombok.Data;\n");
            sb.append("import lombok.NoArgsConstructor;\n");
        }
        if (!sb.isEmpty()) sb.append("\n");
        sb.append(body);
        return sb.toString();
    }

    /**
     * Emits only the class declaration and field list.
     * @JsonProperty("originalKey") is added when the Java field name
     * differs from the original JSON key (e.g. first_name -> firstName).
     *
     * @param isPublic only the root class is public: every class goes into a
     *                 single output blob, and Java permits at most one public
     *                 top-level type per file.
     */
    private void generateClass(String className, JsonNode node, StringBuilder sb,
          boolean useLombok, boolean detectDates, Set<String> usedTypes,
          StructureModel model, boolean isPublic) {
        Set<String> usedNames = new HashSet<>();
        List<String> fieldNames = new ArrayList<>();
        for (String key : (Iterable<String>) node::fieldNames)
            fieldNames.add(uniqueName(toCamelCase(key), "", usedNames));
        if (useLombok) {
            // Lombok's getter for xAxis is getXAxis, which Jackson reads as the
            // property "xaxis": the class could not read the JSON it was
            // generated from. Binding through the fields keeps their names.
            if (fieldNames.stream().anyMatch(SourceConventions::accessorNameDiffers)) {
                usedTypes.add("JsonAutoDetect");
                sb.append("@JsonAutoDetect(").append(SourceConventions.FIELD_BINDING).append(")\n");
            }
            sb.append("@Data\n");
            sb.append("@NoArgsConstructor\n");
            // On a class with no fields the all-args constructor IS the no-args
            // one, and Lombok then declares the same constructor twice.
            // Every generated field is a reference type (one JVM slot), and
            // the constructor also needs its receiver slot.
            if (!node.isEmpty() && node.size() <= 254) sb.append("@AllArgsConstructor\n");
            else if (node.size() > 254)
                sb.append("// All-args constructor omitted: exceeds the JVM limit of 254 parameters.\n");
        }
        sb.append(isPublic ? "public class " : "class ").append(className).append(" {\n\n");

        int index = 0;
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String originalKey = e.getKey();
            String camelName = fieldNames.get(index++);
            String javaType = resolveJavaType(e.getValue(), originalKey, detectDates,
                  usedTypes, model);
            if (!camelName.equals(originalKey)) {
                if (SourceConventions.isMappableKey(originalKey)) {
                    usedTypes.add("JsonProperty");
                    sb.append("    @JsonProperty(\"")
                          .append(SourceConventions.javaStringLiteral(originalKey))
                          .append("\")\n");
                } else {
                    sb.append("    // ").append(SourceConventions.UNMAPPABLE_KEY_NOTE).append("\n");
                }
            }
            sb.append("    private ").append(javaType).append(" ").append(camelName).append(";\n");
        }

        sb.append("}\n");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String resolveJavaType(JsonNode node, String fieldName, boolean detectDates,
          Set<String> usedTypes, StructureModel model) {
        if (node.isInt() || node.isShort())  return "Integer";
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
        if (node.isNull())                   return "Object";
        if (node.isObject()) {
            // The name assigned during collection — not a recomputation, which
            // would silently point at another object's class on a collision.
            String assigned = model.nameOf(node);
            return assigned != null ? assigned : capitalize(toCamelCase(fieldName));
        }
        if (node.isArray()) {
            usedTypes.add("List");
            // The merged shape of every element, not element 0: [1,"two"] was
            // List<Integer>, and an object appearing later with extra keys lost them.
            JsonNode element = model.elementOf(node);
            if (element == null) return "List<Object>";
            return "List<" + resolveJavaType(element, fieldName, detectDates, usedTypes, model) + ">";
        }
        return "Object";
    }

    private String toCamelCase(String s) {
        return SourceConventions.toCamelCase(s, ILLEGAL_IN_IDENTIFIER, JAVA_KEYWORDS);
    }
}
