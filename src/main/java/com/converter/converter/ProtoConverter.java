package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.*;

/**
 * Bidirectional Protobuf schema (proto3) converter — no protoc required.
 *
 * protoToJson : parses proto3 message blocks -> JSON structure with typed defaults.
 * jsonToProto : walks a JSON structure -> proto3 .proto schema.
 *
 * NOTE: autoClose / array-unwrapping is handled upstream in ConverterPanel.dispatch()
 *       before the JSON reaches this class.
 */
public class ProtoConverter {

    private final ObjectMapper jsonMapper =
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final Pattern MSG_PATTERN =
        Pattern.compile("message\\s+(\\w+)\\s*\\{([^}]*?)\\}", Pattern.DOTALL);
    private static final Pattern FIELD_PATTERN =
        Pattern.compile("(repeated\\s+)?(\\w+)\\s+(\\w+)\\s*=\\s*\\d+\\s*;");

    // ── proto -> JSON ─────────────────────────────────────────────────────
    public String protoToJson(String protoSchema) throws Exception {
        String clean = protoSchema.replaceAll("//[^\\n]*", "").trim();
        ObjectNode root = jsonMapper.createObjectNode();

        Matcher msgMatcher = MSG_PATTERN.matcher(clean);
        boolean found = false;
        while (msgMatcher.find()) {
            found = true;
            String     className = msgMatcher.group(1);
            String     body      = msgMatcher.group(2);
            ObjectNode msgNode   = jsonMapper.createObjectNode();

            Matcher fm = FIELD_PATTERN.matcher(body);
            while (fm.find()) {
                boolean repeated  = fm.group(1) != null;
                String  protoType = fm.group(2);
                String  fieldName = fm.group(3);
                if (repeated) {
                    msgNode.putArray(fieldName);
                } else {
                    switch (protoType) {
                        case "string"                          -> msgNode.put(fieldName, "");
                        case "int32","sint32","uint32",
                             "fixed32","sfixed32"              -> msgNode.put(fieldName, 0);
                        case "int64","sint64","uint64",
                             "fixed64","sfixed64"              -> msgNode.put(fieldName, 0L);
                        case "float"                           -> msgNode.put(fieldName, 0.0f);
                        case "double"                          -> msgNode.put(fieldName, 0.0);
                        case "bool"                            -> msgNode.put(fieldName, false);
                        case "bytes"                           -> msgNode.put(fieldName, "");
                        default -> msgNode.putObject(fieldName);
                    }
                }
            }
            root.set(className, msgNode);
        }

        if (!found)
            throw new IllegalArgumentException(
                "No 'message' blocks found. Example:\n\n" +
                "message Person {\n  string name = 1;\n  int32 age = 2;\n}");

        return jsonMapper.writeValueAsString(root);
    }

    // ── JSON -> proto ─────────────────────────────────────────────────────
    public String jsonToProto(String json) throws Exception {
        JsonNode root = jsonMapper.readTree(json);

        // Unwrap root array — first element is the representative schema
        if (root.isArray()) {
            if (root.size() == 0)
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            root = root.get(0);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("syntax = \"proto3\";\n\n");

        LinkedHashMap<String, JsonNode> messages = new LinkedHashMap<>();
        collectMessages(root, "Root", messages, new HashSet<>());

        for (Map.Entry<String, JsonNode> entry : messages.entrySet()) {
            int[] counter = {1};
            generateMessage(entry.getKey(), entry.getValue(), sb, counter);
            sb.append("\n");
        }
        return sb.toString();
    }

    private void collectMessages(JsonNode node, String name,
                                  LinkedHashMap<String, JsonNode> messages,
                                  Set<String> visited) {
        if (!node.isObject() || visited.contains(name)) return;
        visited.add(name);
        messages.put(name, node);
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String   childName = capitalize(e.getKey());
            JsonNode child     = e.getValue();
            if (child.isObject()) {
                collectMessages(child, childName, messages, visited);
            } else if (child.isArray() && !child.isEmpty() && child.get(0).isObject()) {
                collectMessages(child.get(0), childName, messages, visited);
            }
        }
    }

    private void generateMessage(String msgName, JsonNode node,
                                  StringBuilder sb, int[] counter) {
        sb.append("message ").append(msgName).append(" {\n");
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String   fieldName = e.getKey();
            JsonNode val       = e.getValue();
            String   childName = capitalize(fieldName);

            if (val.isArray()) {
                String elemType = (!val.isEmpty() && val.get(0).isObject())
                    ? childName : jsonTypeToProto(!val.isEmpty() ? val.get(0) : null);
                sb.append("  repeated ").append(elemType).append(" ")
                  .append(fieldName).append(" = ").append(counter[0]++).append(";\n");
            } else if (val.isObject()) {
                sb.append("  ").append(childName).append(" ")
                  .append(fieldName).append(" = ").append(counter[0]++).append(";\n");
            } else {
                sb.append("  ").append(jsonTypeToProto(val)).append(" ")
                  .append(fieldName).append(" = ").append(counter[0]++).append(";\n");
            }
        }
        sb.append("}\n");
    }

    private String jsonTypeToProto(JsonNode val) {
        if (val == null || val.isNull())   return "string";
        if (val.isBoolean())               return "bool";
        if (val.isInt() || val.isShort())  return "int32";
        if (val.isLong())                  return "int64";
        if (val.isFloat())                 return "float";
        if (val.isDouble())                return "double";
        if (val.isBigDecimal())            return "double";
        if (val.isTextual())               return "string";
        return "string";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
