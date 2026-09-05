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

    // No INDENT_OUTPUT: writes the internal pivot only, which is re-parsed.
    // A plain mapper on purpose: PivotJson keeps decimals as BigDecimal,
    // which is right for carrying values through a conversion but would
    // retype every JSON 1.5 here, and these classify number SHAPES.
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Matches a single proto field statement in a message body.
     * Groups: (1) repeated/optional prefix  (2) type (dotted / generic)
     *         (3) field name  (4) field number.
     */
    /**
     * Optional trailing field-option list, e.g. {@code [deprecated = true]}.
     * Standard proto3 and common in real schemas; without it a single annotated
     * field made the whole enclosing message fail to parse.
     */
    private static final String FIELD_OPTIONS = "(?:\\s*\\[[^\\]]*\\])?";

    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(repeated\\s+|optional\\s+)?" +
        "([\\w.]+(?:\\s*<[^>]+>)?)" +
        "\\s+(\\w+)" +
        "\\s*=\\s*(\\d+)" +
        FIELD_OPTIONS +
        "\\s*;");

    /**
     * Permissive field statement (split on ';' and trimmed): allows dotted
     * types (google.protobuf.Timestamp) and generic types (map&lt;k, v&gt;).
     */
    private static final Pattern STATEMENT_PATTERN = Pattern.compile(
        "(?:repeated\\s+|optional\\s+|required\\s+)?[\\w.]+(?:\\s*<[^>]*>)?\\s+(\\w+)\\s*=\\s*(\\d+)"
              + FIELD_OPTIONS,
        Pattern.DOTALL);

    /** Statements that are legal proto3 but irrelevant for structural conversion. */
    private static final Pattern IGNORED_STATEMENT = Pattern.compile(
        "^(option|reserved|package|import|syntax)\\b.*", Pattern.DOTALL);

    /** A single enum value statement: {@code NAME = number}. */
    private static final Pattern ENUM_VALUE_PATTERN = Pattern.compile(
        "(\\w+)\\s*=\\s*-?\\d+" + FIELD_OPTIONS, Pattern.DOTALL);

    private static final Set<String> SCALAR_TYPES = Set.of(
        "string", "int32", "sint32", "uint32", "fixed32", "sfixed32",
        "int64", "sint64", "uint64", "fixed64", "sfixed64",
        "float", "double", "bool", "bytes"
    );

    // ── Internal data structure ───────────────────────────────────────────

    private static final class Block {
        final String name;
        final String body;
        final int start;
        final int end;
        /** For a message: the types declared directly inside it. Set at registration. */
        Scope inner;
        Block(String name, String body, int start, int end) {
            this.name = name;
            this.body = body;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * The type names visible from inside one message: the types it declares
     * itself, then its enclosing message's, out to the file's top level.
     *
     * <p>protoc resolves a bare type name by searching exactly that way,
     * innermost scope outward. One flat registry keyed by simple name gave
     * every same-named nested type the LAST definition registered — message
     * A's {@code Inner i = 1} resolved to message B's {@code Inner} — and let a
     * nested type shadow a top-level one for every other message in the file.
     */
    private static final class Scope {
        final Scope parent;
        final Map<String, Block> messages = new LinkedHashMap<>();
        final Map<String, String> enumDefaults = new LinkedHashMap<>();

        Scope(Scope parent) { this.parent = parent; }

        /** The message {@link Block} or enum-default {@link String} declared directly here, or null. */
        Object member(String name) {
            Block message = messages.get(name);
            return message != null ? message : enumDefaults.get(name);
        }
    }

    // ── proto -> JSON ─────────────────────────────────────────────────────

    public String protoToJson(String protoSchema) throws Exception {
        if (protoSchema == null || protoSchema.isBlank())
            throw new IllegalArgumentException(
                "Protobuf input is empty. Paste a proto3 schema containing at least one 'message' block.");

        String clean = maskCommentsAndStrings(protoSchema).trim();
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

        Scope fileScope = new Scope(null);
        // Top-level enums are what is left once every message block is removed.
        // Searching the whole text found the nested ones too and registered
        // them at file level, where any message could see them.
        for (Block en : findNamedBlocks(stripBlocks(clean, "message"), "enum"))
            fileScope.enumDefaults.put(en.name, firstEnumValue(en));
        for (Block msg : topMessages) register(msg, fileScope);

        // Every message is validated, not only the ones a field happens to
        // reference. buildMessageNode validates as it descends, so a nested
        // message nothing pointed at was never checked at all — its javadoc
        // said each message body is validated, and it was not.
        for (Block msg : topMessages) validateTree(msg);

        ObjectNode root = jsonMapper.createObjectNode();
        for (Block msg : topMessages) {
            root.set(msg.name, buildMessageNode(msg, new HashSet<>()));
        }

        return jsonMapper.writeValueAsString(root);
    }

    // ── Registration ──────────────────────────────────────────────────────

    /** Registers a message in its enclosing scope, and what it declares in a scope of its own. */
    private void register(Block msg, Scope enclosing) {
        Scope own = new Scope(enclosing);
        msg.inner = own;
        enclosing.messages.put(msg.name, msg);
        // Enums of the nested messages belong to those messages, so they are
        // stripped before the search; the nested messages themselves are found
        // with depth tracking and register their own contents recursively.
        for (Block en : findNamedBlocks(stripBlocks(msg.body, "message"), "enum"))
            own.enumDefaults.put(en.name, firstEnumValue(en));
        for (Block nested : findNamedBlocks(msg.body, "message")) register(nested, own);
    }

    /** An enum's first declared value — the proto3 default — or "" when it declares none. */
    private String firstEnumValue(Block en) {
        for (String raw : en.body.split(";")) {
            String stmt = raw.trim();
            if (stmt.isEmpty() || IGNORED_STATEMENT.matcher(stmt).matches()) continue;
            Matcher m = ENUM_VALUE_PATTERN.matcher(stmt);
            if (m.matches()) return m.group(1);
        }
        return "";
    }

    /**
     * What a type name denotes from inside {@code scope}: a message {@link Block},
     * an enum's default value {@link String}, or null when nothing matches.
     *
     * <p>A bare name is searched innermost scope outward. A dotted name resolves
     * its first part the same way and then descends through nested scopes; when
     * the first part is unknown — a package prefix, which this parser does not
     * model — the search retries from the next part, so {@code pkg.A.Inner}
     * still finds {@code A.Inner}.
     */
    private static Object resolveType(Scope scope, String type) {
        String qualified = type.startsWith(".") ? type.substring(1) : type;
        String[] parts = qualified.split("\\.");
        for (int start = 0; start < parts.length; start++) {
            Object current = lookup(scope, parts[start]);
            int i = start + 1;
            while (current instanceof Block owner && i < parts.length) {
                current = owner.inner.member(parts[i]);
                i++;
            }
            if (current != null && i == parts.length) return current;
        }
        return null;
    }

    private static Object lookup(Scope scope, String name) {
        for (Scope s = scope; s != null; s = s.parent) {
            Object member = s.member(name);
            if (member != null) return member;
        }
        return null;
    }

    // ── JSON node construction ────────────────────────────────────────────

    /**
     * @param resolving the messages currently being expanded, by identity: a
     *                  name would conflate two same-named nested messages, and
     *                  a recursive type has to bottom out as an empty object.
     */
    private ObjectNode buildMessageNode(Block msg, Set<Block> resolving) {
        ObjectNode node = jsonMapper.createObjectNode();
        if (!resolving.add(msg)) return node;

        try {
            String flatBody = stripBlocks(msg.body, "message", "oneof", "enum");
            // Not validated here: validateTree already covered every message,
            // referenced or not, before any of this ran. Doing it again split
            // the same bodies on ';' and re-matched them for a second time, and
            // left two paths that could disagree about which error a user sees.
            addFields(flatBody, node, msg.inner, resolving);

            for (Block oneof : findNamedBlocks(msg.body, "oneof"))
                addFields(oneof.body, node, msg.inner, resolving);
        } finally {
            resolving.remove(msg);
        }
        return node;
    }

    private void addFields(String body, ObjectNode node, Scope scope, Set<Block> resolving) {
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
            } else {
                Object type = resolveType(scope, protoType);
                if (type instanceof String enumDefault) {
                    node.put(fieldName, enumDefault);
                } else if (type instanceof Block message && !resolving.contains(message)) {
                    node.set(fieldName, buildMessageNode(message, resolving));
                } else {
                    node.putObject(fieldName);
                }
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

    // ── Lexing ────────────────────────────────────────────────────────────

    /**
     * Blanks the contents of comments and string literals with spaces, leaving
     * every other character (and the overall length) untouched.
     *
     * <p>Structural scanning counts braces and looks for {@code message}/{@code
     * enum} keywords over raw text, so without this a literal brace in
     * {@code option x = "{";} is counted as a real block opener and a valid
     * schema is rejected as unbalanced. Doing it in one pass — rather than with
     * separate comment and string regexes — is what keeps {@code "//"} inside a
     * string and a quote inside a comment from confusing each other.
     */
    static String maskCommentsAndStrings(String input) {
        char[] out = input.toCharArray();
        int i = 0, n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c == '/' && i + 1 < n && input.charAt(i + 1) == '/') {
                while (i < n && input.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && input.charAt(i + 1) == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(input.charAt(i) == '*'
                      && i + 1 < n && input.charAt(i + 1) == '/')) {
                    if (input.charAt(i) != '\n') out[i] = ' ';   // keep line structure
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                out[i++] = ' ';                                   // opening quote
                while (i < n && input.charAt(i) != c) {
                    boolean escaped = input.charAt(i) == '\\';
                    out[i++] = ' ';
                    if (escaped && i < n) out[i++] = ' ';         // escaped char
                }
                if (i < n) out[i++] = ' ';                        // closing quote
            } else {
                i++;
            }
        }
        return new String(out);
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
    /** Validates a message and every message nested inside it, referenced or not. */
    private void validateTree(Block msg) {
        List<Block> oneofs = findNamedBlocks(msg.body, "oneof");
        Set<String> seenNumbers = new HashSet<>();
        validateMessageBody(msg.name, stripBlocks(msg.body, "message", "oneof", "enum"), seenNumbers);
        for (Block oneof : oneofs) validateMessageBody(msg.name, oneof.body, seenNumbers);
        for (Block nested : findNamedBlocks(msg.body, "message")) validateTree(nested);
    }

    private void validateMessageBody(String messageName, String body, Set<String> seenNumbers) {
        // Text after the last ';' is a statement that never terminated. It
        // validated fine — STATEMENT_PATTERN does not require the semicolon —
        // while addFields uses FIELD_PATTERN, which does, so the field was
        // silently dropped and "message M { string a = 1 }" produced {}.
        int lastSemicolon = body.lastIndexOf(';');
        String trailing = (lastSemicolon < 0 ? body : body.substring(lastSemicolon + 1)).trim();
        // Only when it is otherwise a WELL-FORMED field. A malformed one falls
        // through to the loop below, whose message names the expected form and
        // is the more useful of the two.
        if (!trailing.isEmpty() && !trailing.contains("{") && !trailing.contains("}")
              && !IGNORED_STATEMENT.matcher(trailing).matches()
              && STATEMENT_PATTERN.matcher(trailing).matches())
            throw new IllegalArgumentException(
                  "Field \"" + trailing + "\" in message '" + messageName + "' is missing its "
                  + "terminating ';'. Without it the field cannot be read and would be dropped.");

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

        // Peels every level, not just one: the POJO and data class generators
        // unwrap a root array of arrays all the way down, and the root must not
        // disagree with the same shape one level in.
        int unwrapped = 0;
        while (root.isArray()) {
            if (root.isEmpty())
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            root = root.get(0);
            unwrapped++;
        }

        if (!root.isObject())
            throw new IllegalArgumentException(
                "JSON root must be an object (or an array of objects) to generate a Protobuf schema, "
                // Naming the leaf type after unwrapping would report "number" for
                // [[1,2]], a type the user never wrote at the root.
                + (unwrapped == 0
                      ? "but got: " + root.getNodeType().name().toLowerCase()
                      : "but its innermost element is: "
                            + root.getNodeType().name().toLowerCase()));

        StringBuilder sb = new StringBuilder();
        sb.append("syntax = \"proto3\";\n\n");
        generateMessage("Root", root, sb, 0);
        return sb.toString();
    }

    private void generateMessage(String msgName, JsonNode node,
                                  StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        sb.append(pad).append("message ").append(msgName).append(" {\n");

        // Field names are assigned first because they carry the JSON mapping and
        // so must not move. protoc registers nested type names and field names in
        // ONE symbol table per message, so seeding the message names with them is
        // what stops "message Foo" landing beside a field also called Foo —
        // '"Foo" is already defined in "Root"'.
        Set<String> usedFieldNames     = new HashSet<>();
        Map<String, String> fieldNames = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties())
            fieldNames.put(e.getKey(), protoFieldName(e.getKey(), usedFieldNames));

        // Nested message names must be unique within this message: keys "user"
        // and "User" both want to be "User", which would emit two blocks of the
        // same name. Assign once here, then reuse for the block and the field
        // type so the two can never disagree.
        Map<String, String> childNames     = new LinkedHashMap<>();
        Map<String, List<String>> rowNames = new LinkedHashMap<>();
        Set<String> usedMessageNames       = new HashSet<>(usedFieldNames);
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode val  = e.getValue();
            JsonNode leaf = arrayLeaf(val);
            if (leaf != null && leaf.isObject()) {
                childNames.put(e.getKey(),
                      uniqueName(protoMessageName(e.getKey()), "", usedMessageNames));
            }
            // proto3 has no "repeated repeated", so every array level past the
            // first needs a message of its own to be repeated inside. Without
            // them the extra levels — and every field of the object at the
            // bottom — were silently replaced by "repeated string".
            int depth = arrayDepth(val);
            if (depth > MAX_ARRAY_DEPTH)
                throw new IllegalArgumentException(String.format(
                      "Field \"%s\" nests arrays %d deep. proto3 has no repeated-of-repeated, so "
                      + "each level needs a wrapper message; past %d that is noise rather than a "
                      + "schema. Flatten the field, or convert to a format that has nested lists.",
                      e.getKey(), depth, MAX_ARRAY_DEPTH));
            if (depth >= 2) {
                List<String> rows = new ArrayList<>();
                for (int level = 2; level <= depth; level++)
                    rows.add(uniqueName(protoMessageName(e.getKey()) + "Row", "", usedMessageNames));
                rowNames.put(e.getKey(), rows);
            }
        }

        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode val       = e.getValue();
            String   childName = childNames.get(e.getKey());
            if (childName != null)
                generateMessage(childName, arrayLeaf(val), sb, indent + 1);

            List<String> rows = rowNames.get(e.getKey());
            if (rows == null) continue;
            String elemType = (childName != null) ? childName : jsonTypeToProto(arrayLeaf(val));
            for (String row : rows) {            // innermost level first
                generateArrayWrapper(row, elemType, sb, indent + 1);
                elemType = row;                  // the level above repeats this one
            }
        }

        int[] counter = {1};
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String   fieldName = fieldNames.get(e.getKey());
            JsonNode val       = e.getValue();
            String   childName = childNames.get(e.getKey());
            String   fieldPad  = pad + "  ";

            if (val.isArray()) {
                List<String> rows = rowNames.get(e.getKey());
                // The outermost wrapper is what this field repeats; the inner
                // ones are already chained to each other above.
                String elemType = rows != null ? rows.get(rows.size() - 1)
                      : (childName != null ? childName : jsonTypeToProto(arrayLeaf(val)));
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
     * Emits the message standing in for one array level, since proto3 cannot
     * repeat a repeated field. This is the encoding the schema's author would
     * have to write by hand for a 2D array.
     */
    private void generateArrayWrapper(String name, String elementType,
                                      StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        sb.append(pad).append("message ").append(name).append(" {\n")
          .append(pad).append("  repeated ").append(elementType).append(" values = 1;\n")
          .append(pad).append("}\n");
    }

    /**
     * Array nesting past which a schema stops being worth generating. Each level
     * costs a wrapper message, so an unbounded depth turned a 1.6 MB document
     * into a 50 MB schema of nothing but wrappers.
     */
    private static final int MAX_ARRAY_DEPTH = 8;

    /** Nested array levels: 0 for a non-array, 1 for {@code [1]}, 2 for {@code [[1]]}. */
    private static int arrayDepth(JsonNode node) {
        int depth = 0;
        while (node != null && node.isArray()) {
            depth++;
            node = node.isEmpty() ? null : node.get(0);
        }
        return depth;
    }

    /**
     * The element an array bottoms out at — the node itself when it is not an
     * array, and null when some level is empty and there is nothing to type
     * from. Arrays are typed from their first element at every level, matching
     * {@link StructureModel}.
     */
    private static JsonNode arrayLeaf(JsonNode node) {
        while (node != null && node.isArray()) node = node.isEmpty() ? null : node.get(0);
        return node;
    }

    /** Suffixes a counter until {@code base} is unused, recording the result in {@code used}. */
    private String uniqueName(String base, String separator, Set<String> used) {
        if (used.add(base)) return base;
        int n = 2;
        while (!used.add(base + separator + n)) n++;
        return base + separator + n;
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
        return uniqueName(sb.toString(), "_", used);
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
