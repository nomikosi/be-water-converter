package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.util.*;

/**
 * Generates Java POJO class skeletons from a JSON or XML structure.
 * Each class contains only field declarations (with @JsonProperty where the
 * JSON key differs from the camelCase Java name). No constructor, getters,
 * setters, toString, equals, or hashCode are emitted — add those via your IDE
 * or a Lombok annotation such as @Data.
 */
public class JavaPojoGenerator {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final XmlMapper   xmlMapper   = new XmlMapper();

    public String fromJson(String json) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        String safe = autoClose(json);
        JsonNode root = jsonMapper.readTree(safe);
        if (root.isArray()) {
            if (root.size() == 0)
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            root = root.get(0);
        }
        return generate(root, "Root");
    }

    public String fromXml(String xml) throws Exception {
        JsonNode root = xmlMapper.readTree(xml.getBytes());
        if (root.isArray()) {
            if (root.size() == 0)
                throw new IllegalArgumentException("XML array is empty — nothing to generate.");
            root = root.get(0);
        }
        return generate(root, "Root");
    }

    // ── Internal generation ───────────────────────────────────────────────

    private String generate(JsonNode root, String rootClassName) {
        LinkedHashMap<String, JsonNode> classMap = new LinkedHashMap<>();
        collectClasses(root, rootClassName, classMap, new HashSet<>());
        StringBuilder sb = new StringBuilder();
        sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        sb.append("import java.math.BigDecimal;\n");
        sb.append("import java.util.List;\n\n");
        for (Map.Entry<String, JsonNode> entry : classMap.entrySet()) {
            generateClass(entry.getKey(), entry.getValue(), sb);
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
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
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
    private void generateClass(String className, JsonNode node, StringBuilder sb) {
        sb.append("public class ").append(className).append(" {\n\n");

        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String originalKey = e.getKey();
            String camelName   = toCamelCase(originalKey);
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
        if (node.isFloat())                  return "Float";
        if (node.isDouble())                 return "Double";
        if (node.isBigDecimal())             return "BigDecimal";
        if (node.isBoolean())                return "Boolean";
        if (node.isTextual())                return "String";
        if (node.isNull())                   return "Object";
        if (node.isObject())                 return capitalize(toCamelCase(fieldName));
        if (node.isArray()) {
            if (node.size() == 0) return "List<?>";
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
        String first = parts[0];
        String head  = first.isEmpty() ? first
              : Character.toLowerCase(first.charAt(0)) + first.substring(1);
        StringBuilder sb = new StringBuilder(sanitize(head));
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty())
                sb.append(Character.toUpperCase(parts[i].charAt(0)))
                      .append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private String sanitize(String s) { return s.replaceAll("[^a-zA-Z0-9_$]", "_"); }

    private String autoClose(String json) {
        if (json == null) return "";
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false, escape = false;
        for (char c : json.toCharArray()) {
            if (escape)         { escape = false; continue; }
            if (c == '\\')   { if (inString) escape = true; continue; }
            if (c == '"')      { inString = !inString; continue; }
            if (inString)       continue;
            if (c == '{')      stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') { if (!stack.isEmpty()) stack.pop(); }
        }
        StringBuilder sb = new StringBuilder(json);
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
    }
}
