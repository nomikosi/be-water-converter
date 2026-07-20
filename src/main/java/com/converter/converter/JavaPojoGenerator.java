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
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generates Java POJO class skeletons from a JSON or XML structure.
 * Each class contains only field declarations (with @JsonProperty where the
 * JSON key differs from the camelCase Java name). Constructors and accessors
 * are not emitted; enable Lombok mode to annotate the generated classes with
 * @Data, @NoArgsConstructor, and @AllArgsConstructor instead.
 */
public class JavaPojoGenerator {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final XmlMapper   xmlMapper   = new XmlMapper();

    private static final Set<String> JAVA_KEYWORDS = Set.of(
          "abstract", "assert", "boolean", "break", "byte", "case", "catch",
          "char", "class", "const", "continue", "default", "do", "double",
          "else", "enum", "extends", "final", "finally", "float", "for",
          "goto", "if", "implements", "import", "instanceof", "int",
          "interface", "long", "native", "new", "package", "private",
          "protected", "public", "return", "short", "static", "strictfp",
          "super", "switch", "synchronized", "this", "throw", "throws",
          "transient", "try", "void", "volatile", "while",
          "var", "yield", "record", "sealed", "permits");

    public String fromJson(String json) throws Exception {
        return fromJson(json, false);
    }

    public String fromJson(String json, boolean useLombok) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        JsonNode root = jsonMapper.readTree(json);
        if (root.isArray()) {
            if (root.size() == 0)
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            root = root.get(0);
        }
        return generate(root, "Root", useLombok);
    }

    public String fromXml(String xml) throws Exception {
        return fromXml(xml, false);
    }

    public String fromXml(String xml, boolean useLombok) throws Exception {
        if (xml == null || xml.isBlank())
            throw new IllegalArgumentException("Input XML must not be null or blank");
        JsonNode root = xmlMapper.readTree(xml.getBytes(StandardCharsets.UTF_8));
        if (root.isArray()) {
            if (root.size() == 0)
                throw new IllegalArgumentException("XML array is empty — nothing to generate.");
            root = root.get(0);
        }
        return generate(root, "Root", useLombok);
    }

    // ── Internal generation ───────────────────────────────────────────────

    private String generate(JsonNode root, String rootClassName, boolean useLombok) {
        LinkedHashMap<String, JsonNode> classMap = new LinkedHashMap<>();
        collectClasses(root, rootClassName, classMap, new HashSet<>());
        StringBuilder sb = new StringBuilder();
        sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        sb.append("import java.math.BigDecimal;\n");
        sb.append("import java.math.BigInteger;\n");
        sb.append("import java.util.List;\n");
        if (useLombok) {
            sb.append("import lombok.AllArgsConstructor;\n");
            sb.append("import lombok.Data;\n");
            sb.append("import lombok.NoArgsConstructor;\n");
        }
        sb.append("\n");
        for (Map.Entry<String, JsonNode> entry : classMap.entrySet()) {
            generateClass(entry.getKey(), entry.getValue(), sb, useLombok);
            sb.append("\n");
        }
        return sb.toString();
    }

    private void collectClasses(JsonNode node, String className,
          LinkedHashMap<String, JsonNode> classMap,
          Set<String> visited) {
        if (!node.isObject() || visited.contains(className)) return;
        visited.add(className);
        classMap.put(className, node);
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String childName = capitalize(toCamelCase(entry.getKey()));
            JsonNode child = entry.getValue();
            if (child.isObject()) {
                collectClasses(child, childName, classMap, visited);
            } else if (child.isArray() && child.size() > 0 && child.get(0).isObject()) {
                collectClasses(child.get(0), childName, classMap, visited);
            }
        }
    }

    /**
     * Emits only the class declaration and field list.
     * @JsonProperty("originalKey") is added when the Java field name
     * differs from the original JSON key (e.g. first_name -> firstName).
     */
    private void generateClass(String className, JsonNode node, StringBuilder sb,
          boolean useLombok) {
        if (useLombok) {
            sb.append("@Data\n");
            sb.append("@NoArgsConstructor\n");
            sb.append("@AllArgsConstructor\n");
        }
        sb.append("public class ").append(className).append(" {\n\n");

        Set<String> usedNames = new HashSet<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String originalKey = e.getKey();
            String camelName   = toCamelCase(originalKey);
            // Two keys may normalise to the same Java name (user_name / userName);
            // suffix a counter so the generated class still compiles.
            if (!usedNames.add(camelName)) {
                int n = 2;
                while (!usedNames.add(camelName + n)) n++;
                camelName = camelName + n;
            }
            String javaType    = resolveJavaType(e.getValue(), originalKey);
            if (!camelName.equals(originalKey)) {
                sb.append("    @JsonProperty(\"").append(originalKey).append("\")\n");
            }
            sb.append("    private ").append(javaType).append(" ").append(camelName).append(";\n");
        }

        sb.append("}\n");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String resolveJavaType(JsonNode node, String fieldName) {
        if (node.isInt() || node.isShort())  return "Integer";
        if (node.isLong())                   return "Long";
        if (node.isBigInteger())             return "BigInteger";
        if (node.isFloat())                  return "Float";
        if (node.isDouble())                 return "Double";
        if (node.isBigDecimal())             return "BigDecimal";
        if (node.isBoolean())                return "Boolean";
        if (node.isTextual())                return "String";
        if (node.isNull())                   return "Object";
        if (node.isObject())                 return capitalize(toCamelCase(fieldName));
        if (node.isArray()) {
            if (node.size() == 0) return "List<Object>";
            return "List<" + resolveJavaType(node.get(0), fieldName) + ">";
        }
        return "Object";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] parts = s.split("[_\\-.]+");
        StringBuilder sb = new StringBuilder();
        for (String rawPart : parts) {
            if (rawPart.isEmpty()) continue;
            String part = sanitize(rawPart);
            if (sb.isEmpty()) {
                sb.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1).toLowerCase());
            }
        }
        if (sb.isEmpty()) sb.append('_');
        String result = sb.toString();
        if (Character.isDigit(result.charAt(0))) {
            result = "_" + result;
        }
        if (JAVA_KEYWORDS.contains(result)) {
            result = result + "Value";
        }
        return result;
    }

    private String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9_$]", "_"); }
}
