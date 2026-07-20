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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.*;

/**
 * Bidirectional Protobuf schema (proto3) converter — no protoc required.
 *
 * <ul>
 *   <li>{@link #protoToJson} parses proto3 message blocks (including nested
 *       {@code message} and {@code oneof} blocks) into a JSON structure with
 *       typed defaults.  Field types that reference known messages are resolved
 *       to nested objects instead of producing empty placeholders.</li>
 *   <li>{@link #jsonToProto} walks a JSON structure and emits a proto3 schema
 *       with inline nested messages and repeated fields.</li>
 * </ul>
 */
public class ProtoConverter {

    private final ObjectMapper jsonMapper =
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Matches a single proto field statement in a message body.
     * Groups: (1) repeated/optional prefix  (2) type (dotted / generic)
     *         (3) field name  (4) field number.
     */
    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(repeated\\s+|optional\\s+)?" +
        "([\\w.]+(?:\\s*<[^>]+>)?)" +
        "\\s+(\\w+)" +
        "\\s*=\\s*(\\d+)" +
        "\\s*;");

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

    /** A single enum value statement: {@code NAME = number}. */
    private static final Pattern ENUM_VALUE_PATTERN = Pattern.compile(
        "(\\w+)\\s*=\\s*-?\\d+", Pattern.DOTALL);

    private static final Set<String> SCALAR_TYPES = Set.of(
        "string", "int32", "sint32", "uint32", "fixed32", "sfixed32",
        "int64", "sint64", "uint64", "fixed64", "sfixed64",
        "float", "double", "bool", "bytes"
    );

    // ── Internal data structure ───────────────────────────────────────────

    private static class Block {
        final String name;
        final String body;
        final int start;
        final int end;
        Block(String name, String body, int start, int end) {
            this.name = name;
            this.body = body;
            this.start = start;
            this.end = end;
        }
    }

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

        List<Block> topMessages = findNamedBlocks(clean, "message");

        if (topMessages.isEmpty()) {
            if (clean.contains("message"))
                throw new IllegalArgumentException(
                    "Found the 'message' keyword but could not parse any message block. " +
                    "Check that each block has a name and balanced braces, e.g.:\n\n" +
                    "message Person {\n  string name = 1;\n  int32 age = 2;\n}");
            throw new IllegalArgumentException(
                "No 'message' blocks found. Example:\n\n" +
                "message Person {\n  string name = 1;\n  int32 age = 2;\n}");
        }

        Map<String, Block> registry = new LinkedHashMap<>();
        Map<String, String> enumDefaults = new LinkedHashMap<>();
        for (Block en : findNamedBlocks(clean, "enum")) registerEnum(en, enumDefaults);
        for (Block msg : topMessages) registerAll(msg, registry, enumDefaults);

        ObjectNode root = jsonMapper.createObjectNode();
        for (Block msg : topMessages) {
            root.set(msg.name, buildMessageNode(msg, registry, enumDefaults, new HashSet<>()));
        }

        return jsonMapper.writeValueAsString(root);
    }

    // ── Registration ──────────────────────────────────────────────────────

    private void registerAll(Block msg, Map<String, Block> registry, Map<String, String> enums) {
        registry.put(msg.name, msg);
        for (Block en : findNamedBlocks(msg.body, "enum")) registerEnum(en, enums);
        for (Block nested : findNamedBlocks(msg.body, "message")) {
            registerAll(nested, registry, enums);
        }
    }

    /** Registers an enum by name with its first declared value (the proto3 default). */
    private void registerEnum(Block en, Map<String, String> enums) {
        String first = "";
        for (String raw : en.body.split(";")) {
            String stmt = raw.trim();
            if (stmt.isEmpty() || IGNORED_STATEMENT.matcher(stmt).matches()) continue;
            Matcher m = ENUM_VALUE_PATTERN.matcher(stmt);
            if (m.matches()) { first = m.group(1); break; }
        }
        enums.putIfAbsent(en.name, first);
    }

    // ── JSON node construction ────────────────────────────────────────────

    private ObjectNode buildMessageNode(Block msg, Map<String, Block> registry,
                                        Map<String, String> enums, Set<String> resolving) {
        ObjectNode node = jsonMapper.createObjectNode();
        if (!resolving.add(msg.name)) return node;

        try {
            for (Block nested : findNamedBlocks(msg.body, "message"))
                registry.putIfAbsent(nested.name, nested);

            List<Block> oneofs = findNamedBlocks(msg.body, "oneof");

            String flatBody = stripBlocks(msg.body, "message", "oneof", "enum");
            Set<String> seenNumbers = new HashSet<>();
            validateMessageBody(msg.name, flatBody, seenNumbers);
            for (Block oneof : oneofs)
                validateMessageBody(msg.name, oneof.body, seenNumbers);

            addFields(flatBody, node, registry, enums, resolving);

            for (Block oneof : oneofs)
                addFields(oneof.body, node, registry, enums, resolving);
        } finally {
            resolving.remove(msg.name);
        }
        return node;
    }

    private void addFields(String body, ObjectNode node, Map<String, Block> registry,
                           Map<String, String> enums, Set<String> resolving) {
        Matcher fm = FIELD_PATTERN.matcher(body);
        while (fm.find()) {
            boolean repeated  = fm.group(1) != null && fm.group(1).trim().equals("repeated");
            String  protoType = fm.group(2).trim();
            String  fieldName = fm.group(3);

            if (repeated) {
                node.putArray(fieldName);
            } else if (protoType.startsWith("map<") || protoType.startsWith("map <")) {
                node.putObject(fieldName);
            } else if (SCALAR_TYPES.contains(protoType)) {
                addScalarDefault(node, fieldName, protoType);
            } else if (enums.containsKey(protoType)) {
                node.put(fieldName, enums.get(protoType));
            } else if (registry.containsKey(protoType) && !resolving.contains(protoType)) {
                node.set(fieldName,
                      buildMessageNode(registry.get(protoType), registry, enums, resolving));
            } else {
                node.putObject(fieldName);
            }
        }
    }

    private void addScalarDefault(ObjectNode node, String fieldName, String type) {
        switch (type) {
            case "string", "bytes"                                   -> node.put(fieldName, "");
            case "int32", "sint32", "uint32", "fixed32", "sfixed32"  -> node.put(fieldName, 0);
            case "int64", "sint64", "uint64", "fixed64", "sfixed64"  -> node.put(fieldName, 0L);
            case "float"                                             -> node.put(fieldName, 0.0f);
            case "double"                                            -> node.put(fieldName, 0.0);
            case "bool"                                              -> node.put(fieldName, false);
            default                                                  -> node.put(fieldName, "");
        }
    }

    // ── Block finding (brace-depth aware) ─────────────────────────────────

    /**
     * Finds all {@code keyword Name { … }} blocks at the current level,
     * using brace-depth tracking so nested braces are handled correctly.
     */
    private List<Block> findNamedBlocks(String input, String keyword) {
        List<Block> blocks = new ArrayList<>();
        int searchFrom = 0;

        while (searchFrom < input.length()) {
            int kwIdx = indexOfWord(input, keyword, searchFrom);
            if (kwIdx < 0) break;

            int afterKw = kwIdx + keyword.length();
            int nameStart = afterKw;
            while (nameStart < input.length() && Character.isWhitespace(input.charAt(nameStart)))
                nameStart++;

            int nameEnd = nameStart;
            while (nameEnd < input.length() &&
                   (Character.isLetterOrDigit(input.charAt(nameEnd)) || input.charAt(nameEnd) == '_'))
                nameEnd++;

            if (nameEnd == nameStart) { searchFrom = afterKw; continue; }

            String name = input.substring(nameStart, nameEnd);

            int bracePos = nameEnd;
            while (bracePos < input.length() && Character.isWhitespace(input.charAt(bracePos)))
                bracePos++;

            if (bracePos >= input.length() || input.charAt(bracePos) != '{') {
                searchFrom = nameEnd;
                continue;
            }

            int bodyStart = bracePos + 1;
            int depth = 1, pos = bodyStart;
            while (pos < input.length() && depth > 0) {
                if (input.charAt(pos) == '{') depth++;
                else if (input.charAt(pos) == '}') depth--;
                pos++;
            }

            if (depth != 0) { searchFrom = nameEnd; continue; }

            blocks.add(new Block(name, input.substring(bodyStart, pos - 1), kwIdx, pos));
            searchFrom = pos;
        }
        return blocks;
    }

    /** Finds {@code keyword} as a whole word (not preceded/followed by word characters). */
    private int indexOfWord(String input, String keyword, int from) {
        int idx = from;
        while (idx <= input.length() - keyword.length()) {
            idx = input.indexOf(keyword, idx);
            if (idx < 0) return -1;

            boolean leftOk = (idx == 0) ||
                  !(Character.isLetterOrDigit(input.charAt(idx - 1)) || input.charAt(idx - 1) == '_');
            int end = idx + keyword.length();
            boolean rightOk = (end >= input.length()) ||
                  !(Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_');

            if (leftOk && rightOk) return idx;
            idx = end;
        }
        return -1;
    }

    /** Strips all named blocks for the given keywords from the input. */
    private String stripBlocks(String input, String... keywords) {
        String result = input;
        for (String kw : keywords) {
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            for (Block b : findNamedBlocks(result, kw)) {
                sb.append(result, lastEnd, b.start);
                lastEnd = b.end;
            }
            sb.append(result.substring(lastEnd));
            result = sb.toString();
        }
        return result;
    }

    // ── Validation ────────────────────────────────────────────────────────

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
     * with a precise error instead of being silently skipped.  Also rejects
     * duplicate field numbers; {@code seenNumbers} is shared across the flat
     * body and all oneof bodies of the same message.
     */
    private void validateMessageBody(String messageName, String body, Set<String> seenNumbers) {
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
        generateMessage("Root", root, sb, 0);
        return sb.toString();
    }

    private void generateMessage(String msgName, JsonNode node,
                                  StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        sb.append(pad).append("message ").append(msgName).append(" {\n");

        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String   childName = protoMessageName(e.getKey());
            JsonNode val       = e.getValue();
            if (val.isObject()) {
                generateMessage(childName, val, sb, indent + 1);
            } else if (val.isArray() && !val.isEmpty() && val.get(0).isObject()) {
                generateMessage(childName, val.get(0), sb, indent + 1);
            }
        }

        int[] counter = {1};
        Set<String> usedFieldNames = new HashSet<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String   fieldName = protoFieldName(e.getKey(), usedFieldNames);
            JsonNode val       = e.getValue();
            String   childName = protoMessageName(e.getKey());
            String   fieldPad  = pad + "  ";

            if (val.isArray()) {
                String elemType = (!val.isEmpty() && val.get(0).isObject())
                    ? childName : jsonTypeToProto(!val.isEmpty() ? val.get(0) : null);
                sb.append(fieldPad).append("repeated ").append(elemType).append(" ")
                  .append(fieldName).append(" = ").append(counter[0]++).append(";\n");
            } else if (val.isObject()) {
                sb.append(fieldPad).append(childName).append(" ")
                  .append(fieldName).append(" = ").append(counter[0]++).append(";\n");
            } else {
                sb.append(fieldPad).append(jsonTypeToProto(val)).append(" ")
                  .append(fieldName).append(" = ").append(counter[0]++).append(";\n");
            }
        }
        sb.append(pad).append("}\n");
    }

    /**
     * Maps an arbitrary JSON key to a valid proto field identifier
     * (snake_case-ish: invalid characters become underscores, a leading digit
     * gets a prefix) and deduplicates within the message.
     */
    private String protoFieldName(String key, Set<String> used) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                  || (c >= '0' && c <= '9') || c == '_';
            sb.append(ok ? c : '_');   // proto identifiers are ASCII-only
        }
        if (sb.isEmpty()) sb.append('_');
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        String name = sb.toString();
        if (!used.add(name)) {
            int n = 2;
            while (!used.add(name + "_" + n)) n++;
            name = name + "_" + n;
        }
        return name;
    }

    /** Sanitized, capitalized message name for a JSON key. */
    private String protoMessageName(String key) {
        String base = protoFieldName(key, new HashSet<>());
        return capitalize(base);
    }

    private String jsonTypeToProto(JsonNode val) {
        if (val == null || val.isNull())   return "string";
        if (val.isBoolean())               return "bool";
        if (val.isInt() || val.isShort())  return "int32";
        if (val.isLong() || val.isBigInteger()) return "int64";
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
