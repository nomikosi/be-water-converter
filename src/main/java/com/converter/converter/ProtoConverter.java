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

    /**
     * Permissive field statement (split on ';' and trimmed): allows dotted
     * types (google.protobuf.Timestamp) and generic types (map&lt;k, v&gt;).
     */
    private static final Pattern STATEMENT_PATTERN = Pattern.compile(
        "(?:repeated\\s+|optional\\s+|required\\s+)?[\\w.]+(?:\\s*<[^>]*>)?\\s+(\\w+)\\s*=\\s*(\\d+)",
        Pattern.DOTALL);

    /** Statements that are legal proto3 but irrelevant for structural conversion. */
    private static final Pattern IGNORED_STATEMENT = Pattern.compile(
        "^(option|reserved|package|import|syntax)\\b.*", Pattern.DOTALL);

    // ── proto -> JSON ─────────────────────────────────────────────────────
    public String protoToJson(String protoSchema) throws Exception {
        if (protoSchema == null || protoSchema.isBlank())
            throw new IllegalArgumentException(
                "Protobuf input is empty. Paste a proto3 schema containing at least one 'message' block.");

        String clean = protoSchema
            .replaceAll("(?s)/\\*.*?\\*/", "")   // block comments
            .replaceAll("//[^\\n]*", "")          // line comments
            .trim();
        validateBraces(clean);

        ObjectNode root = jsonMapper.createObjectNode();

        Matcher msgMatcher = MSG_PATTERN.matcher(clean);
        boolean found = false;
        while (msgMatcher.find()) {
            found = true;
            String     className = msgMatcher.group(1);
            String     body      = msgMatcher.group(2);
            validateMessageBody(className, body);
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

        if (!found) {
            if (clean.contains("message"))
                throw new IllegalArgumentException(
                    "Found the 'message' keyword but could not parse any message block. " +
                    "Check that each block has a name and balanced braces, e.g.:\n\n" +
                    "message Person {\n  string name = 1;\n  int32 age = 2;\n}");
            throw new IllegalArgumentException(
                "No 'message' blocks found. Example:\n\n" +
                "message Person {\n  string name = 1;\n  int32 age = 2;\n}");
        }

        return jsonMapper.writeValueAsString(root);
    }

    // ── Validation helpers ────────────────────────────────────────────────

    private void validateBraces(String schema) {
        int open = 0, close = 0;
        for (char c : schema.toCharArray()) {
            if (c == '{') open++;
            else if (c == '}') close++;
        }
        if (open != close)
            throw new IllegalArgumentException(
                "Unbalanced braces: found " + open + " '{' but " + close + " '}'. " +
                "Make sure every message block is properly closed.");
    }

    /**
     * Validates each statement inside a message body so malformed fields fail
     * with a precise error instead of being silently skipped. Also rejects
     * duplicate field numbers within the same message. Statements containing
     * braces (oneof blocks, nested message definitions) are left to the
     * lenient structural parser and are not validated here.
     */
    private void validateMessageBody(String messageName, String body) {
        Set<String> seenNumbers = new HashSet<>();
        for (String rawStatement : body.split(";")) {
            String stmt = rawStatement.trim();
            if (stmt.isEmpty()
                  || stmt.contains("{") || stmt.contains("}")
                  || IGNORED_STATEMENT.matcher(stmt).matches()) continue;

            Matcher m = STATEMENT_PATTERN.matcher(stmt);
            if (!m.matches())
                throw new IllegalArgumentException(
                    "Invalid field in message '" + messageName + "': \"" + stmt + "\". " +
                    "Expected the form: [repeated] <type> <name> = <number>;");

            String number = m.group(2);
            if (!seenNumbers.add(number))
                throw new IllegalArgumentException(
                    "Duplicate field number " + number + " in message '" + messageName +
                    "'. Each field must have a unique number.");
        }
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

        if (!root.isObject())
            throw new IllegalArgumentException(
                "JSON root must be an object (or an array of objects) to generate a Protobuf schema, " +
                "but got: " + root.getNodeType().name().toLowerCase());

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
