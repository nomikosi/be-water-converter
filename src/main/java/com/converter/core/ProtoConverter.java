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

import static com.converter.core.SourceConventions.capitalize;
import static com.converter.core.SourceConventions.uniqueName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // Used only to construct and serialize structural defaults.
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

    /** An integer as protoc spells one: decimal, 0x hexadecimal, or 0-prefixed octal. */
    private static final String FIELD_NUMBER = "0[xX][0-9a-fA-F]+|\\d+";

    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(repeated\\s+|optional\\s+)?" +
        "([\\w.]+(?:\\s*<[^>]+>)?)" +
        // A map type needs no space before the name: map<string,string>labels.
        "(?:\\s+|(?<=>)\\s*)(\\w+)" +
        "\\s*=\\s*(" + FIELD_NUMBER + ")" +
        FIELD_OPTIONS +
        "\\s*;");

    /**
     * Permissive field statement (split on ';' and trimmed): allows dotted
     * types (google.protobuf.Timestamp) and generic types (map&lt;k, v&gt;).
     */
    private static final Pattern STATEMENT_PATTERN = Pattern.compile(
        "(?:repeated\\s+|optional\\s+|required\\s+)?[\\w.]+(?:\\s*<[^>]*>)?(?:\\s+|(?<=>)\\s*)(\\w+)"
              + "\\s*=\\s*(" + FIELD_NUMBER + ")" + FIELD_OPTIONS,
        Pattern.DOTALL);

    /**
     * Statements that are legal but irrelevant for structural conversion.
     * {@code extensions 1000 to max;} is proto2's and editions', and failed
     * whole files — descriptor.proto among them — as an invalid field.
     */
    private static final Pattern IGNORED_STATEMENT = Pattern.compile(
        "^(option|reserved|package|import|syntax|edition|extensions)\\b.*", Pattern.DOTALL);

    /** A field's {@code default} option, proto2's. */
    private static final Pattern DEFAULT_OPTION = Pattern.compile("(?:\\[|,)\\s*default\\s*=");

    /** A default written as a token rather than a string: a number, an enum value, true, inf. */
    private static final Pattern DEFAULT_TOKEN = Pattern.compile("[-+]?[\\w.]+");

    /**
     * proto2's {@code [label] group Name = N [options] { ... }}: a message and
     * the field holding it, declared in one statement.
     */
    private static final Pattern GROUP_HEADER = Pattern.compile(
        "\\b(?:(optional|required|repeated)\\s+)?group\\s+(\\w+)\\s*=\\s*(" + FIELD_NUMBER + ")"
              + FIELD_OPTIONS + "\\s*\\{");

    /** The start of an option whose value is a text-format aggregate. */
    private static final Pattern OPTION_AGGREGATE = Pattern.compile("\\boption\\b[^;{}]*=\\s*\\{");

    /** What proto3's JSON mapping reads any JSON value into. */
    private static final String STRUCT_VALUE = "google.protobuf.Value";

    /** A single enum value statement: {@code NAME = number}. */
    private static final Pattern ENUM_VALUE_PATTERN = Pattern.compile(
        "(\\w+)\\s*=\\s*-?(?:" + FIELD_NUMBER + ")" + FIELD_OPTIONS, Pattern.DOTALL);

    private static final Pattern JSON_NAME_OPTION =
          Pattern.compile("(?:\\[|,)\\s*json_name\\s*=");

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
        final int bodyOffset;
        /** For a message: the types declared directly inside it. Set at registration. */
        Scope inner;
        /** For a message: the proto2 groups declared directly in its body. Set at registration. */
        List<Group> groups = List.of();
        Block(String name, String body, int start, int end, int bodyOffset) {
            this.name = name;
            this.body = body;
            this.start = start;
            this.end = end;
            this.bodyOffset = bodyOffset;
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
    /**
     * A proto2 group: its message, and the field that holds it, named after the
     * group in lower case as protoc names it. Positions are within the
     * enclosing message's body.
     */
    private record Group(Block block, boolean repeated, String fieldName, String number, int start, int end) {}

    private static final class Scope {
        final Scope parent;
        final Map<String, Scope> packages = new LinkedHashMap<>();
        final Map<String, Block> messages = new LinkedHashMap<>();
        final Map<String, String> enumDefaults = new LinkedHashMap<>();

        Scope(Scope parent) { this.parent = parent; }

        /** A package scope, message block, or enum default declared directly here, or null. */
        Object member(String name) {
            Scope pkg = packages.get(name);
            if (pkg != null) return pkg;
            Block message = messages.get(name);
            return message != null ? message : enumDefaults.get(name);
        }
    }

    // ── proto -> JSON ─────────────────────────────────────────────────────

    public String protoToJson(String protoSchema) throws Exception {
        if (protoSchema == null || protoSchema.isBlank())
            throw new IllegalArgumentException(
                "Protobuf input is empty. Paste a proto3 schema containing at least one 'message' block.");

        String clean = flattenOptionAggregates(flattenOptionGroups(maskCommentsAndStrings(protoSchema)));
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
        Matcher packageName = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;")
              .matcher(stripBlocks(clean, "message", "enum", "service"));
        if (packageName.find()) {
            for (String part : packageName.group(1).split("\\.")) {
                Scope child = new Scope(fileScope);
                fileScope.packages.put(part, child);
                fileScope = child;
            }
        }
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
        long[] expanded = {0};
        for (Block msg : topMessages) {
            root.set(msg.name, buildMessageNode(msg, new HashSet<>(), protoSchema, expanded));
        }

        return jsonMapper.writeValueAsString(root);
    }

    // ── Registration ──────────────────────────────────────────────────────

    /** Registers a message in its enclosing scope, and what it declares in a scope of its own. */
    private void register(Block msg, Scope enclosing) {
        Scope own = new Scope(enclosing);
        msg.inner = own;
        enclosing.messages.put(msg.name, msg);
        // A group is a nested message too, named as written: its fields are
        // its own, and they were read as fields of the message around it. What
        // it declares is its own as well, so the groups are blanked before
        // this message's own types are searched for.
        msg.groups = groups(ownBody(msg), msg.body, msg.bodyOffset);
        String body = withoutGroups(msg.body, msg.groups);
        // Enums of the nested messages belong to those messages, so they are
        // stripped before the search; the nested messages themselves are found
        // with depth tracking and register their own contents recursively.
        for (Block en : findNamedBlocks(stripBlocks(body, "message"), "enum"))
            own.enumDefaults.put(en.name, firstEnumValue(en));
        for (Block nested : findNamedBlocks(body, "message", msg.bodyOffset)) register(nested, own);
        for (Group group : msg.groups) register(group.block(), own);
    }

    /**
     * This message's own oneofs: not those of the messages nested in it, nor
     * those of its groups, which are the groups' own.
     */
    private List<Block> ownOneofs(Block msg) {
        return findNamedBlocks(withoutGroups(stripBlocks(msg.body, "message"), msg.groups), "oneof", msg.bodyOffset);
    }

    /** A message's body without the blocks of what it declares, offsets kept. */
    private String ownBody(Block msg) {
        return stripBlocks(msg.body, "message", "oneof", "enum", "extend");
    }

    /**
     * The groups written directly in a message: found in {@code own}, its body
     * with what it declares blanked, and read from {@code body}, the same text
     * whole, since a group declares oneofs and types of its own.
     */
    private List<Group> groups(String own, String body, int offset) {
        List<Group> groups = new ArrayList<>();
        Matcher m = GROUP_HEADER.matcher(own);
        int from = 0;
        while (m.find(from)) {
            int bodyStart = m.end();
            int depth = 1, pos = bodyStart;
            while (pos < own.length() && depth > 0) {
                char c = own.charAt(pos);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                pos++;
            }
            if (depth != 0) break;       // unbalanced: validateBraces reports it
            Block block = new Block(m.group(2), body.substring(bodyStart, pos - 1), m.start(), pos,
                  offset + bodyStart);
            groups.add(new Group(block, "repeated".equals(m.group(1)),
                  m.group(2).toLowerCase(Locale.ROOT), m.group(3), m.start(), pos));
            from = pos;
        }
        return groups;
    }

    /** {@code body} with its group statements blanked, offsets kept. */
    private static String withoutGroups(String body, List<Group> groups) {
        if (groups.isEmpty()) return body;
        char[] out = body.toCharArray();
        for (Group group : groups) Arrays.fill(out, group.start(), group.end(), ' ');
        return new String(out);
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

    /** Resolves relative names from the nearest scope, and absolute names from the root. */
    private static Object resolveType(Scope scope, String type) {
        boolean absolute = type.startsWith(".");
        String[] parts = (absolute ? type.substring(1) : type).split("\\.");
        if (absolute) {
            while (scope.parent != null) scope = scope.parent;
            return descend(scope.member(parts[0]), parts);
        }
        for (Scope current = scope; current != null; current = current.parent) {
            Object first = current.member(parts[0]);
            // Once the first component binds, missing descendants do not make
            // an unrelated outer declaration (or an arbitrary suffix) a match.
            if (first != null) return descend(first, parts);
        }
        return null;
    }

    private static Object descend(Object current, String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            Scope inner = current instanceof Block owner ? owner.inner
                  : current instanceof Scope pkg ? pkg : null;
            if (inner == null) return null;
            current = inner.member(parts[i]);
        }
        return current;
    }

    // ── JSON node construction ────────────────────────────────────────────

    /**
     * @param resolving the messages currently being expanded, by identity: a
     *                  name would conflate two same-named nested messages, and
     *                  a recursive type has to bottom out as an empty object.
     */
    private ObjectNode buildMessageNode(Block msg, Set<Block> resolving, String source, long[] expanded) {
        ObjectNode node = jsonMapper.createObjectNode();
        if (!resolving.add(msg)) return node;

        try {
            // An extend block declares fields of ANOTHER message; they were
            // being listed as this message's own.
            String flatBody = withoutGroups(ownBody(msg), msg.groups);
            // Not validated here: validateTree already covered every message,
            // referenced or not, before any of this ran. Doing it again split
            // the same bodies on ';' and re-matched them for a second time, and
            // left two paths that could disagree about which error a user sees.
            addFields(flatBody, msg.groups, node, msg.inner, resolving, source, msg.bodyOffset, expanded);

            for (Block oneof : ownOneofs(msg))
                addFields(oneof.body, List.of(), node, msg.inner, resolving, source, oneof.bodyOffset, expanded);
        } finally {
            resolving.remove(msg);
        }
        return node;
    }

    /**
     * Values a schema may expand to before it is refused. Every singular
     * message field is filled in with its own default, so two fields of the
     * next message type at each level double the output per level: a
     * 600-character schema twenty levels deep ran a 1 GB heap out of memory.
     * The same ceiling YAML aliases get.
     */
    static final long MAX_EXPANDED_VALUES = 2_000_000;

    private void addFields(String body, List<Group> groups, ObjectNode node, Scope scope,
          Set<Block> resolving, String source, int bodyOffset, long[] expanded) {
        Matcher fm = FIELD_PATTERN.matcher(body);
        int nextGroup = 0;
        while (fm.find()) {
            // Groups in declaration order among the fields around them.
            while (nextGroup < groups.size() && groups.get(nextGroup).start() < fm.start())
                addGroup(groups.get(nextGroup++), node, resolving, source, expanded);
            countExpanded(expanded);
            boolean repeated  = fm.group(1) != null && fm.group(1).trim().equals("repeated");
            String  protoType = fm.group(2).trim();
            String  fieldName = fm.group(3);
            Matcher jsonName = JSON_NAME_OPTION.matcher(fm.group());
            if (jsonName.find()) {
                fieldName = ProtoStringLiteral.read(source, bodyOffset + fm.start() + jsonName.end());
                if (jsonName.find())
                    throw new IllegalArgumentException("Duplicate json_name option for field " + fm.group(3));
            }
            if (node.has(fieldName))
                throw new IllegalArgumentException("Duplicate JSON field name: " + fieldName);

            // proto2 fields may say what their default is: [default = 10].
            Matcher explicitDefault = DEFAULT_OPTION.matcher(fm.group());
            int defaultAt = explicitDefault.find() ? bodyOffset + fm.start() + explicitDefault.end() : -1;

            if (repeated) {
                node.putArray(fieldName);
            } else if (protoType.startsWith("map<") || protoType.startsWith("map <")) {
                node.putObject(fieldName);
            } else if (SCALAR_TYPES.contains(protoType)) {
                if (defaultAt < 0 || !putExplicitDefault(node, fieldName, protoType, source, defaultAt))
                    addScalarDefault(node, fieldName, protoType);
            } else {
                Object type = resolveType(scope, protoType);
                if (type instanceof String enumDefault) {
                    String written = defaultAt < 0 ? null : defaultToken(source, defaultAt);
                    node.put(fieldName, written != null && written.matches("[A-Za-z_]\\w*") ? written : enumDefault);
                } else if (type instanceof Block message && !resolving.contains(message)) {
                    node.set(fieldName, buildMessageNode(message, resolving, source, expanded));
                } else {
                    node.putObject(fieldName);
                }
            }
        }
        while (nextGroup < groups.size()) addGroup(groups.get(nextGroup++), node, resolving, source, expanded);
    }

    private static void countExpanded(long[] expanded) {
        if (++expanded[0] > MAX_EXPANDED_VALUES)
            throw new IllegalArgumentException(String.format(
                  "This schema expands to more than %,d values when every message field is "
                  + "filled in with its default: messages holding several fields of the same "
                  + "message type multiply at each level of nesting. Convert the messages you "
                  + "need on their own, or flatten the nesting.", MAX_EXPANDED_VALUES));
    }

    /** A group's field: a list when repeated, otherwise its message with defaults. */
    private void addGroup(Group group, ObjectNode node, Set<Block> resolving, String source, long[] expanded) {
        countExpanded(expanded);
        if (node.has(group.fieldName()))
            throw new IllegalArgumentException("Duplicate JSON field name: " + group.fieldName());
        if (group.repeated()) node.putArray(group.fieldName());
        else if (!resolving.contains(group.block()))
            node.set(group.fieldName(), buildMessageNode(group.block(), resolving, source, expanded));
        else node.putObject(group.fieldName());
    }

    /** The token a {@code default} option is written as, or null when it is a string or missing. */
    private static String defaultToken(String source, int at) {
        Matcher token = DEFAULT_TOKEN.matcher(source);
        token.region(skipSpace(source, at), source.length());
        return token.lookingAt() ? token.group() : null;
    }

    private static int skipSpace(String source, int i) {
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        return i;
    }

    /**
     * Writes a scalar field's explicit default, as proto3's JSON mapping would:
     * bytes in base64, and an infinite or NaN float as "Infinity" or "NaN".
     * False when the default cannot be read, and the type's own default is used.
     */
    private static boolean putExplicitDefault(ObjectNode node, String fieldName, String type,
          String source, int at) {
        int start = skipSpace(source, at);
        if (start < source.length() && (source.charAt(start) == '"' || source.charAt(start) == '\'')) {
            byte[] bytes = ProtoStringLiteral.readBytes(source, start);
            if (type.equals("bytes")) node.put(fieldName, java.util.Base64.getEncoder().encodeToString(bytes));
            else if (type.equals("string")) node.put(fieldName, ProtoStringLiteral.utf8(bytes));
            else return false;
            return true;
        }
        String token = defaultToken(source, at);
        if (token == null) return false;
        try {
            switch (type) {
                case "bool" -> {
                    if (!token.equals("true") && !token.equals("false")) return false;
                    node.put(fieldName, token.equals("true"));
                }
                case "float", "double" -> {
                    String lower = token.toLowerCase(Locale.ROOT);
                    if (lower.equals("inf") || lower.equals("+inf")) node.put(fieldName, "Infinity");
                    else if (lower.equals("-inf")) node.put(fieldName, "-Infinity");
                    else if (lower.equals("nan") || lower.equals("-nan")) node.put(fieldName, "NaN");
                    else node.put(fieldName, new java.math.BigDecimal(token));
                }
                case "string", "bytes" -> { return false; }
                default -> node.put(fieldName, integerLiteral(token));
            }
        } catch (NumberFormatException unreadable) {
            return false;
        }
        return true;
    }

    /** An integer as protoc writes one: decimal, 0x hexadecimal or 0-prefixed octal, signed. */
    private static java.math.BigInteger integerLiteral(String token) {
        boolean negative = token.startsWith("-");
        String digits = token.startsWith("-") || token.startsWith("+") ? token.substring(1) : token;
        java.math.BigInteger value = digits.startsWith("0x") || digits.startsWith("0X")
              ? new java.math.BigInteger(digits.substring(2), 16)
              : digits.length() > 1 && digits.startsWith("0") ? new java.math.BigInteger(digits, 8)
              : new java.math.BigInteger(digits);
        return negative ? value.negate() : value;
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

    /**
     * Blanks the brackets, braces and semicolons nested INSIDE a field's
     * {@code [...]} option list, keeping its outer brackets and everything
     * else in place, so offsets into the source still line up.
     *
     * <p>Options can hold whole messages and lists — protovalidate's
     * {@code [(buf.validate.field).string = {in: ["a", "b"]}]} — and the field
     * patterns took the first {@code ]} as the end of the list. The field was
     * then silently skipped, and a duplicate number after it went unchecked.
     */
    static String flattenOptionAggregates(String masked) {
        char[] out = masked.toCharArray();
        Matcher m = OPTION_AGGREGATE.matcher(masked);
        int from = 0;
        while (from < masked.length() && m.find(from)) {
            int depth = 1, j = m.end();
            while (j < out.length && depth > 0) {
                char c = masked.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth > 0 && c != '\n' && c != '\r') out[j] = ' ';
                j++;
            }
            from = j;
        }
        return new String(out);
    }

    static String flattenOptionGroups(String masked) {
        char[] out = masked.toCharArray();
        for (int i = 0; i < out.length; i++) {
            if (out[i] != '[') continue;
            int depth = 1, j = i + 1;
            while (j < out.length && depth > 0) {
                char c = out[j];
                if (c == '[') depth++;
                else if (c == ']') depth--;
                if (depth > 0 && (c == '[' || c == ']' || c == '{' || c == '}' || c == ';')) out[j] = ' ';
                j++;
            }
            i = j - 1;
        }
        return new String(out);
    }

    // ── Block finding (brace-depth aware) ─────────────────────────────────

    /**
     * Finds all {@code keyword Name { … }} blocks at the current level,
     * using brace-depth tracking so nested braces are handled correctly.
     */
    private List<Block> findNamedBlocks(String input, String keyword) {
        return findNamedBlocks(input, keyword, 0);
    }

    private List<Block> findNamedBlocks(String input, String keyword, int offset) {
        List<Block> blocks = new ArrayList<>();
        int searchFrom = 0;

        while (searchFrom < input.length()) {
            int kwIdx = indexOfWord(input, keyword, searchFrom);
            if (kwIdx < 0) break;

            int afterKw = kwIdx + keyword.length();
            int nameStart = afterKw;
            while (nameStart < input.length() && Character.isWhitespace(input.charAt(nameStart)))
                nameStart++;

            // Dots for "extend google.protobuf.FieldOptions {": the only block
            // named by a type reference rather than a plain identifier.
            int nameEnd = nameStart;
            while (nameEnd < input.length() &&
                   (Character.isLetterOrDigit(input.charAt(nameEnd)) || input.charAt(nameEnd) == '_'
                         || input.charAt(nameEnd) == '.'))
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

            blocks.add(new Block(name, input.substring(bodyStart, pos - 1), kwIdx, pos, offset + bodyStart));
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

    /** Blanks blocks, retaining offsets into the original source for field options. */
    private String stripBlocks(String input, String... keywords) {
        String result = input;
        for (String kw : keywords) {
            char[] masked = result.toCharArray();
            for (Block block : findNamedBlocks(result, kw))
                Arrays.fill(masked, block.start, block.end, ' ');
            result = new String(masked);
        }
        return result;
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * How deep blocks may nest, as XML, JSON and TOML may. Messages are read
     * recursively, a level of stack each: 5,000 nested messages overflowed a
     * worker thread's stack, and 50,000, copying each body for the level
     * inside it, exhausted a 2 GB heap.
     */
    static final int MAX_NESTING_DEPTH = 1_000;

    private void validateBraces(String schema) {
        int open = 0, close = 0, depth = 0;
        for (char c : schema.toCharArray()) {
            if (c == '{') {
                open++;
                if (++depth > MAX_NESTING_DEPTH)
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                          "Conversion stops at %,d levels of nesting, as it does for XML, JSON and TOML, "
                          + "and this schema goes deeper.", MAX_NESTING_DEPTH));
            } else if (c == '}') {
                close++;
                depth--;
            }
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
        // Only this message's own oneofs: searched over the raw body, a oneof
        // inside a nested message was validated against THIS message's numbers,
        // so an inner "int32 x = 1" was reported as a duplicate of the outer one.
        List<Block> oneofs = ownOneofs(msg);
        Set<Long> seenNumbers = new HashSet<>();
        validateMessageBody(msg.name, withoutGroups(ownBody(msg), msg.groups), seenNumbers);
        // A group's number is this message's; the fields inside it are the
        // group's own, and were checked against this message's numbers.
        for (Group group : msg.groups) {
            String stmt = "group " + group.block().name + " = " + group.number();
            long value = fieldNumber(msg.name, stmt, group.number());
            rejectIllegalNumber(msg.name, stmt, group.number(), value);
            if (!seenNumbers.add(value))
                throw new IllegalArgumentException(
                      "Duplicate field number " + group.number() + " in message '" + msg.name
                      + "'. Each field must have a unique number.");
        }
        for (Block oneof : oneofs) validateMessageBody(msg.name, oneof.body, seenNumbers);
        // The registered messages, groups among them, which carry their own groups.
        for (Block nested : msg.inner.messages.values()) validateTree(nested);
    }

    private void validateMessageBody(String messageName, String body, Set<Long> seenNumbers) {
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
            long value = fieldNumber(messageName, stmt, number);
            rejectIllegalNumber(messageName, stmt, number, value);
            if (!seenNumbers.add(value))
                throw new IllegalArgumentException(
                    "Duplicate field number " + number + " in message '" + messageName +
                    "'. Each field must have a unique number.");
        }
    }

    /**
     * protoc's rules for a field number: 1 to 2^29 - 1, with 19000 to 19999
     * reserved for the implementation. A schema breaking them is not a schema
     * protoc will compile, and reading it as one hid that.
     */
    private static void rejectIllegalNumber(String messageName, String stmt, String number, long value) {
        String problem = value == 0 ? "field numbers start at 1"
              : value > 536_870_911L ? "field numbers cannot exceed 536,870,911"
              : value >= 19_000 && value <= 19_999
                    ? "19000 to 19999 are reserved for the protobuf implementation"
              : null;
        if (problem != null)
            throw new IllegalArgumentException(
                  "Field number " + number + " in message '" + messageName + "' (\"" + stmt
                  + "\") is not allowed: " + problem + ".");
    }

    /**
     * A field number's value as protoc reads it: {@code 0x10} is 16 and
     * {@code 010} is octal 8, which the duplicate check has to compare as 8.
     */
    private static long fieldNumber(String messageName, String stmt, String number) {
        try {
            if (number.startsWith("0x") || number.startsWith("0X"))
                return Long.parseLong(number.substring(2), 16);
            if (number.length() > 1 && number.startsWith("0")) return Long.parseLong(number, 8);
            return Long.parseLong(number);
        } catch (NumberFormatException notANumber) {
            if (number.length() > 1 && number.startsWith("0") && number.chars().allMatch(Character::isDigit))
                throw new IllegalArgumentException(
                      "Field number " + number + " in message '" + messageName + "' (\"" + stmt
                      + "\") starts with 0, which makes it octal, and is not a valid octal number.");
            return Long.MAX_VALUE;     // too long: rejected as above the maximum
        }
    }

    // ── JSON -> proto ─────────────────────────────────────────────────────

    public String jsonToProto(String json) throws Exception {
        JsonNode root = GeneratorJson.readTree(json);

        // Peels every level, not just one: the POJO and data class generators
        // unwrap a root array of arrays all the way down, and the root must not
        // disagree with the same shape one level in. Each level is the merged
        // shape of every element, as in those generators.
        ArrayShapes shapes = new ArrayShapes();
        int unwrapped = 0;
        while (root.isArray()) {
            JsonNode element = shapes.elementOf(root);
            if (element == null)
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            root = element;
            unwrapped++;
        }

        if (!root.isObject())
            throw new IllegalArgumentException(
                "JSON root must be an object (or an array of objects) to generate a Protobuf schema, "
                // Naming the leaf type after unwrapping would report "number" for
                // [[1,2]], a type the user never wrote at the root.
                + (unwrapped == 0
                      ? "but got: " + root.getNodeType().name().toLowerCase(Locale.ROOT)
                      : "but its innermost element is: "
                            + root.getNodeType().name().toLowerCase(Locale.ROOT)));

        StringBuilder sb = new StringBuilder();
        sb.append("syntax = \"proto3\";\n\n");
        generateMessage("Root", root, sb, 0, shapes);
        // Field and message names are identifiers, so only a Value type can
        // spell the qualified name.
        if (sb.indexOf(STRUCT_VALUE) >= 0)
            sb.insert("syntax = \"proto3\";\n\n".length(), "import \"google/protobuf/struct.proto\";\n\n");
        return sb.toString();
    }

    private void generateMessage(String msgName, JsonNode node,
                                  StringBuilder sb, int indent, ArrayShapes shapes) {
        String pad = "  ".repeat(indent);
        sb.append(pad).append("message ").append(msgName).append(" {\n");

        // Field names are assigned first because they carry the JSON mapping and
        // so must not move. protoc registers nested type names and field names in
        // ONE symbol table per message, so seeding the message names with them is
        // what stops "message Foo" landing beside a field also called Foo —
        // '"Foo" is already defined in "Root"'.
        Set<String> usedFieldNames     = new HashSet<>();
        Set<String> usedJsonForms      = new HashSet<>();
        Map<String, String> fieldNames = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String name = protoFieldName(e.getKey(), usedJsonForms);
            usedFieldNames.add(name);
            fieldNames.put(e.getKey(), name);
        }
        Map<String, String> jsonNames = jsonNames(fieldNames);
        // protoc refuses a JSON name in square brackets: that is how the JSON
        // mapping names an extension.
        for (String jsonName : jsonNames.values())
            if (jsonName.startsWith("[") && jsonName.endsWith("]"))
                throw new IllegalArgumentException("The key \"" + jsonName + "\" cannot be kept as a "
                      + "Protobuf JSON name: protoc reserves names in square brackets for extensions. "
                      + "Rename the key, or convert to another format.");

        // Nested message names must be unique within this message: keys "user"
        // and "User" both want to be "User", which would emit two blocks of the
        // same name. Assign once here, then reuse for the block and the field
        // type so the two can never disagree.
        Map<String, String> childNames     = new LinkedHashMap<>();
        Map<String, List<String>> rowNames = new LinkedHashMap<>();
        Set<String> usedMessageNames       = new HashSet<>(usedFieldNames);
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode val  = e.getValue();
            JsonNode leaf = shapes.unwrap(val);
            if (leaf != null && leaf.isObject()) {
                childNames.put(e.getKey(),
                      uniqueName(protoMessageName(e.getKey()), "", usedMessageNames));
            }
            // proto3 has no "repeated repeated", so every array level past the
            // first needs a message of its own to be repeated inside. Without
            // them the extra levels — and every field of the object at the
            // bottom — were silently replaced by "repeated string".
            int depth = shapes.depth(val);
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
                generateMessage(childName, shapes.unwrap(val), sb, indent + 1, shapes);

            List<String> rows = rowNames.get(e.getKey());
            if (rows == null) continue;
            String elemType = elementType(val, childName, shapes);
            for (String row : rows) {            // innermost level first
                generateArrayWrapper(row, elemType, sb, indent + 1);
                elemType = row;                  // the level above repeats this one
            }
        }

        int fieldNumber = 1;
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String   fieldName = fieldNames.get(e.getKey());
            JsonNode val       = e.getValue();
            String   childName = childNames.get(e.getKey());
            String   fieldPad  = pad + "  ";
            if (fieldNumber == 19_000) fieldNumber = 20_000;
            String   tail      = " = " + fieldNumber++ + jsonNameOption(jsonNames.get(e.getKey())) + ";\n";

            if (val.isArray()) {
                List<String> rows = rowNames.get(e.getKey());
                // The outermost wrapper is what this field repeats; the inner
                // ones are already chained to each other above.
                String elemType = rows != null ? rows.get(rows.size() - 1)
                      : elementType(val, childName, shapes);
                sb.append(fieldPad).append("repeated ").append(elemType).append(" ")
                  .append(fieldName).append(tail);
            } else if (val.isObject()) {
                sb.append(fieldPad).append(childName).append(" ").append(fieldName).append(tail);
            } else {
                sb.append(fieldPad).append(jsonTypeToProto(val)).append(" ")
                  .append(fieldName).append(tail);
            }
        }
        sb.append(pad).append("}\n");
    }

    /**
     * The {@code json_name} each field needs, by key.
     *
     * <p>A key the field name cannot spell carries its key: protoc maps a field
     * to JSON by its name (or its lowerCamelCase form), so {@code first-name}
     * written as {@code first_name} would read a different key back.
     *
     * <p>So does a field whose JSON name would equal another field's. protoc
     * refuses two fields with one JSON name, custom or derived: {@code user_id}
     * derives {@code userId}, and a second field carrying the key
     * {@code "userId"} was an error. Each field in such a clash is given its
     * own key, and keys are unique, so the loop ends.
     */
    private static Map<String, String> jsonNames(Map<String, String> fieldNames) {
        Map<String, String> custom = new LinkedHashMap<>();
        fieldNames.forEach((key, name) -> { if (!key.equals(name)) custom.put(key, key); });
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, List<String>> byJsonName = new HashMap<>();
            fieldNames.forEach((key, name) -> byJsonName.computeIfAbsent(
                  custom.getOrDefault(key, toJsonName(name)), k -> new ArrayList<>()).add(key));
            for (List<String> keys : byJsonName.values()) {
                if (keys.size() < 2) continue;
                for (String key : keys) changed |= custom.putIfAbsent(key, key) == null;
            }
        }
        return custom;
    }

    private static String jsonNameOption(String jsonName) {
        return jsonName == null ? ""
              : " [json_name = \"" + SourceConventions.javaStringLiteral(jsonName) + "\"]";
    }

    /** protoc's default JSON name: underscores dropped, the letter after each capitalised. */
    static String toJsonName(String fieldName) {
        StringBuilder out = new StringBuilder(fieldName.length());
        boolean capitalizeNext = false;
        for (char c : fieldName.toCharArray()) {
            if (c == '_') capitalizeNext = true;
            else {
                out.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return out.toString();
    }

    /**
     * A field name as protobuf 3.x compared them: lower-cased with the
     * underscores dropped. Two names equal in this form, such as {@code user_id}
     * and {@code userId}, or {@code name} and {@code Name}, were refused as
     * clashing JSON names ("not allowed in proto3"), and those versions are
     * still in wide use.
     */
    static String jsonForm(String fieldName) {
        return fieldName.replace("_", "").toLowerCase(Locale.ROOT);
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

    /**
     * Maps an arbitrary JSON key to a valid proto field identifier
     * (snake_case-ish: invalid characters become underscores, a leading digit
     * gets a prefix) and deduplicates within the message, by {@link #jsonForm}.
     */
    private String protoFieldName(String key, Set<String> usedJsonForms) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                  || (c >= '0' && c <= '9') || c == '_';
            sb.append(ok ? c : '_');   // proto identifiers are ASCII-only
        }
        if (sb.isEmpty()) sb.append('_');
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        String base = sb.toString(), name = base;
        for (int n = 2; !usedJsonForms.add(jsonForm(name)); n++) name = base + "_" + n;
        return name;
    }

    /** Sanitized, capitalized message name for a JSON key. */
    private String protoMessageName(String key) {
        String base = protoFieldName(key, new HashSet<>());
        return capitalize(base);
    }

    /**
     * The type of an array's innermost elements. Objects keep their message.
     * Values of mixed kinds, and scalars beside a null, are
     * {@code google.protobuf.Value}: as {@code repeated string} a list holding
     * 1, "two" and true could not be read back by proto3's JSON mapping, which
     * wants a string for a string field and refuses a null in a list.
     */
    private String elementType(JsonNode array, String childName, ArrayShapes shapes) {
        if (childName != null) return childName;
        JsonNode innermost = array;
        while (shapes.elementOf(innermost) != null && shapes.elementOf(innermost).isArray())
            innermost = shapes.elementOf(innermost);
        if (shapes.hasNullElement(innermost)) return STRUCT_VALUE;
        return jsonTypeToProto(shapes.elementOf(innermost));
    }

    private String jsonTypeToProto(JsonNode val) {
        // Values of different kinds: {"v":1} beside {"v":"x"}.
        if (val != null && val.isMissingNode()) return STRUCT_VALUE;
        if (val == null || val.isNull())   return "string";
        if (val.isBoolean())               return "bool";
        if (val.isInt() || val.isShort())  return "int32";
        if (val.isLong())                  return "int64";
        if (val.isBigInteger())
            throw new IllegalArgumentException("Protobuf generation cannot represent all integral samples"
                  + " as int64. Encode oversized values as JSON strings or use Java/Kotlin output.");
        if (val.isFloat())                 return "float";
        if (val.isDouble())                return "double";
        if (val.isBigDecimal())
            throw new IllegalArgumentException("Protobuf generation cannot preserve " + val
                  + " as double. Encode this value as a JSON string or use Java/Kotlin output.");
        if (val.isTextual())               return "string";
        return "string";
    }

}
