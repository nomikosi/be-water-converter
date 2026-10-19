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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Java {@code .properties} ↔ JSON, keys nested the way Spring Boot binds them.
 *
 * <p>A file is read exactly as {@link java.util.Properties#load(java.io.Reader)}
 * reads it — comments, the three separators, line continuations and escapes —
 * and each key is then split into a path: {@code server.port} is
 * {@code {"server":{"port":…}}}, {@code servers[0].host} the first element of
 * a list, and {@code map[key.with.dots]} a key that keeps its dots. A key that
 * holds a value and also keys below it ({@code a=1} beside {@code a.b=2}) has
 * no JSON or YAML form, and is refused rather than one of the two dropped.
 *
 * <p>Writing flattens a document the same way back. Properties hold no types,
 * so numbers and booleans are written as their text, and JSON {@code null} as
 * an empty value; an empty object or list, which has no key to write, is
 * written as a comment saying so.
 */
public final class PropertiesConverter {

    /** Above this, an index is a typo or an attack on memory, not a list. */
    static final int MAX_INDEX = 10_000;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    // ── Reading, as java.util.Properties reads ───────────────────────────

    /**
     * A logical line: a comment, or an entry with its key and value unescaped.
     *
     * @param blankBefore whether a blank line separates it from the line before
     * @param rawKey      the key as written, escapes and all, for Format to keep
     * @param rawValue    the value as written, its continuations joined
     */
    record Line(int lineNumber, boolean blankBefore, String comment, String key, String value,
                String rawKey, String rawValue) {
        boolean isComment() { return comment != null; }
    }

    /**
     * The file's logical lines, in order, with comments kept for Format and
     * blank lines dropped.
     *
     * <p>A port of {@code Properties.LineReader.readLine}, state for state,
     * rather than a reading of its documentation: a line ending in an odd
     * number of backslashes goes on, the next line's leading whitespace
     * skipped; a {@code #} or {@code !} opens a comment whenever nothing is
     * buffered yet, even on a line a lone backslash continued; and a lone
     * backslash at the end of the file is an entry with an empty key.
     */
    static List<Line> lines(String text) {
        List<Line> lines = new ArrayList<>();
        int n = text.length();
        int off = 0;
        int lineNumber = 1;              // the physical line the next character is on
        int previousEnd = 0;             // the physical line the last logical line ended on
        StringBuilder buf = new StringBuilder();
        while (true) {
            buf.setLength(0);
            boolean skipWhiteSpace = true;
            boolean appendedLineBegin = false;
            boolean precedingBackslash = false;
            int startLine = 0;
            while (true) {
                if (off >= n) {
                    if (buf.isEmpty()) return lines;
                    if (precedingBackslash) buf.setLength(buf.length() - 1);
                    lines.add(entry(startLine, startLine - previousEnd > 1 && !lines.isEmpty(), buf.toString()));
                    return lines;
                }
                char c = text.charAt(off++);
                int at = lineNumber;
                // CR, LF and CRLF each end one physical line.
                if (c == '\r' || c == '\n' && (off < 2 || text.charAt(off - 2) != '\r')) lineNumber++;
                if (skipWhiteSpace) {
                    if (isWhitespace(c)) continue;
                    if (!appendedLineBegin && (c == '\r' || c == '\n')) continue;
                    skipWhiteSpace = false;
                    appendedLineBegin = false;
                }
                if (buf.isEmpty() && (c == '#' || c == '!')) {
                    int end = off;
                    while (end < n && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
                    lines.add(new Line(at, at - previousEnd > 1 && !lines.isEmpty(),
                          text.substring(off - 1, end), null, null, null, null));
                    previousEnd = at;
                    if (end >= n) return lines;
                    off = end + 1;           // the comment's line break, consumed with it
                    lineNumber++;
                    skipWhiteSpace = true;
                    continue;
                }
                if (c != '\n' && c != '\r') {
                    if (startLine == 0) startLine = at;
                    buf.append(c);
                    precedingBackslash = c == '\\' && !precedingBackslash;
                    continue;
                }
                // The end of a physical line.
                if (buf.isEmpty()) {
                    skipWhiteSpace = true;
                    continue;
                }
                if (off >= n) {
                    if (precedingBackslash) buf.setLength(buf.length() - 1);
                    lines.add(entry(startLine, startLine - previousEnd > 1 && !lines.isEmpty(), buf.toString()));
                    return lines;
                }
                if (precedingBackslash) {
                    buf.setLength(buf.length() - 1);
                    skipWhiteSpace = true;
                    appendedLineBegin = true;
                    precedingBackslash = false;
                    if (c == '\r' && text.charAt(off) == '\n') off++;
                    continue;
                }
                lines.add(entry(startLine, startLine - previousEnd > 1 && !lines.isEmpty(), buf.toString()));
                previousEnd = at;
                break;
            }
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    /** Splits a logical line into its key and value, as {@code Properties.load0} does. */
    private static Line entry(int lineNumber, boolean blankBefore, String raw) {
        int len = raw.length();
        int keyEnd = 0;
        boolean precedingBackslash = false;
        boolean hasSeparator = false;
        int valueStart = len;
        while (keyEnd < len) {
            char c = raw.charAt(keyEnd);
            if ((c == '=' || c == ':') && !precedingBackslash) {
                valueStart = keyEnd + 1;
                hasSeparator = true;
                break;
            } else if (isWhitespace(c) && !precedingBackslash) {
                valueStart = keyEnd + 1;
                break;
            }
            precedingBackslash = c == '\\' && !precedingBackslash;
            keyEnd++;
        }
        while (valueStart < len) {
            char c = raw.charAt(valueStart);
            if (!isWhitespace(c)) {
                if (!hasSeparator && (c == '=' || c == ':')) hasSeparator = true;
                else break;
            }
            valueStart++;
        }
        String rawKey = raw.substring(0, keyEnd);
        String rawValue = raw.substring(valueStart);
        return new Line(lineNumber, blankBefore, null, unescape(rawKey, lineNumber), unescape(rawValue, lineNumber),
              rawKey, rawValue);
    }

    /**
     * {@code \t}, {@code \n}, {@code \r}, {@code \f} and {@code \}{@code uXXXX};
     * any other escaped character stands for itself.
     */
    private static String unescape(String s, int lineNumber) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // A lone backslash cannot end a key or value: the line reader
            // takes one at the end of a line for a continuation.
            if (c != '\\' || i + 1 >= s.length()) {
                if (c != '\\') out.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 >= s.length()) throw malformedUnicode(lineNumber);
                    int value = 0;
                    for (int k = 1; k <= 4; k++) {
                        int digit = hexDigit(s.charAt(i + k));
                        if (digit < 0) throw malformedUnicode(lineNumber);
                        value = value << 4 | digit;
                    }
                    out.append((char) value);
                    i += 4;
                }
                default -> out.append(e);
            }
        }
        return out.toString();
    }

    /** ASCII hex only: {@code Character.digit} also takes the digits of other scripts, which Properties refuses. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static IllegalArgumentException malformedUnicode(int lineNumber) {
        return new IllegalArgumentException(String.format(Locale.ROOT,
              "Malformed \\uXXXX escape on line %d: a \\u needs four hex digits after it.", lineNumber));
    }

    // ── Properties to JSON ───────────────────────────────────────────────

    /**
     * @param inferTypes numbers, booleans and {@code null} written as text
     *                   become typed JSON values, as for CSV and XML input
     */
    public String propertiesToJson(String text, boolean inferTypes) throws Exception {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("The properties input is empty. Write one key=value per line.");
        ObjectNode root = NODES.objectNode();
        // The key that first gave each path its shape, to name both keys of a clash.
        Map<List<Object>, String> keyOfPath = new HashMap<>();
        int entries = 0;
        for (Line line : lines(TextDecoder.stripBom(text))) {
            if (line.isComment()) continue;
            entries++;
            JsonNode value = inferTypes ? ScalarInference.infer(line.value(), NODES) : NODES.textNode(line.value());
            put(root, path(line.key(), line.lineNumber()), value, line.key(), keyOfPath, line.lineNumber());
        }
        if (entries == 0)
            throw new IllegalArgumentException("The properties input holds only comments. Write one key=value per line.");
        return LenientJson.COMPACT.writeValueAsString(root);
    }

    /**
     * A key's path, as Spring Boot reads it: names between dots, and bracketed
     * parts — a list index when it is digits, else a map key, which may hold
     * dots. A {@code [} with no {@code ]} after it is an ordinary character.
     */
    static List<Object> path(String key, int lineNumber) {
        List<Object> path = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        boolean nameDue = true;          // at the start, or after a dot: a name comes next, even an empty one
        int i = 0;
        while (i < key.length()) {
            char c = key.charAt(i);
            int close = c == '[' ? key.indexOf(']', i) : -1;
            if (c == '.') {
                if (nameDue || !name.isEmpty()) path.add(name.toString());
                name.setLength(0);
                nameDue = true;
                i++;
            } else if (close > 0) {
                if (!name.isEmpty() || nameDue && i > 0) path.add(name.toString());
                name.setLength(0);
                nameDue = false;
                String inside = key.substring(i + 1, close);
                // At the very start there is no list to index: [0] names a key "0".
                if (!inside.isEmpty() && inside.chars().allMatch(ch -> ch >= '0' && ch <= '9') && !path.isEmpty())
                    path.add(index(key, inside, lineNumber));
                else path.add(inside);
                i = close + 1;
            } else {
                name.append(c);
                i++;
            }
        }
        if (nameDue || !name.isEmpty()) path.add(name.toString());
        return path;
    }

    private static int index(String key, String digits, int lineNumber) {
        String significant = digits.replaceFirst("^0+(?=.)", "");
        if (significant.length() > 9 || Integer.parseInt(significant) > MAX_INDEX)
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                  "The key %s on line %d indexes a list at %s. Every index below it would have to be "
                        + "filled, so indices above %,d are refused.", key, lineNumber, digits, MAX_INDEX));
        return Integer.parseInt(significant);
    }

    private static void put(ObjectNode root, List<Object> path, JsonNode value, String key,
          Map<List<Object>, String> keyOfPath, int lineNumber) {
        JsonNode container = root;
        for (int p = 0; p < path.size(); p++) {
            Object step = path.get(p);
            List<Object> here = List.copyOf(path.subList(0, p + 1));
            JsonNode existing;
            if (step instanceof Integer index) {
                ArrayNode array = (ArrayNode) container;
                while (array.size() <= index) array.addNull();
                existing = array.get(index);
                // A null no key set is a gap left below a higher index.
                if (existing.isNull() && !keyOfPath.containsKey(here)) existing = null;
            } else {
                existing = container.get((String) step);
            }
            JsonNode child;
            if (p == path.size() - 1) {
                if (existing != null && existing.isContainerNode())
                    throw clash(key, lineNumber, here, "a value", existing, keyOfPath);
                // A key written twice: the last value wins, as Properties keeps it.
                child = value;
                keyOfPath.put(here, key);
            } else {
                boolean wantsList = path.get(p + 1) instanceof Integer;
                if (existing != null) {
                    if (wantsList ? !existing.isArray() : !existing.isObject())
                        throw clash(key, lineNumber, here, wantsList ? "a list" : "named keys", existing, keyOfPath);
                    container = existing;
                    continue;
                }
                child = wantsList ? NODES.arrayNode() : NODES.objectNode();
                keyOfPath.putIfAbsent(here, key);
            }
            if (step instanceof Integer index) ((ArrayNode) container).set(index, child);
            else ((ObjectNode) container).set((String) step, child);
            container = child;
        }
    }

    private static IllegalArgumentException clash(String key, int lineNumber, List<Object> path, String wanted,
          JsonNode found, Map<List<Object>, String> keyOfPath) {
        StringBuilder shown = new StringBuilder();
        for (Object step : path) {
            if (step instanceof Integer) shown.append('[').append(step).append(']');
            else if (!isPlainName((String) step)) shown.append('[').append(step).append(']');
            else shown.append(shown.isEmpty() ? "" : ".").append(step);
        }
        String held = found.isArray() ? "a list" : found.isObject() ? "named keys" : "a single value";
        return new IllegalArgumentException(String.format(Locale.ROOT,
              "The key %s on line %d cannot be kept beside the key %s: it needs %s at %s, which already "
                    + "holds %s. JSON and YAML have no form for both, so neither is dropped silently.",
              key, lineNumber, keyOfPath.getOrDefault(path, shown.toString()), wanted, shown, held));
    }

    // ── JSON to properties ───────────────────────────────────────────────

    public String jsonToProperties(String json) throws Exception {
        JsonNode root = LenientJson.COMPACT.readTree(json);
        if (root == null || !root.isObject())
            throw new IllegalArgumentException("Properties need named keys at the top: the JSON root must be an "
                  + "object, not " + (root == null || root.isMissingNode() ? "nothing"
                        : root.isArray() ? "a list" : "a single value") + ".");
        if (root.isEmpty())
            return "# The JSON object is empty: there are no keys to write\n";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, JsonNode> entry : root.properties())
            flatten(entry.getValue(), rootKey(entry.getKey(), entry.getValue()), out);
        return out.toString();
    }

    private static void flatten(JsonNode node, String key, StringBuilder out) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("# ").append(escapeComment(key)).append(" is an empty object, which properties cannot hold\n");
                return;
            }
            for (Map.Entry<String, JsonNode> entry : node.properties())
                flatten(entry.getValue(), childKey(key, entry.getKey()), out);
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("# ").append(escapeComment(key)).append(" is an empty list, which properties cannot hold\n");
                return;
            }
            for (int i = 0; i < node.size(); i++) flatten(node.get(i), key + "[" + i + "]", out);
        } else {
            out.append(escapeKey(key)).append('=')
                  .append(escapeValue(node.isNull() ? "" : node.asText())).append('\n');
        }
    }

    /** A top-level key: written as it is, unless it would read back as a different path. */
    private static String rootKey(String name, JsonNode value) {
        if (isPlainName(name)) return name;
        // "=value" for a value; with keys or elements below it, "[]", since
        // "[x.y]" or "[0]" alone at the start of a key would name a key of
        // the top level instead.
        if (name.isEmpty()) return value.isContainerNode() ? "[]" : "";
        return bracketed(name, "");
    }

    /**
     * The key of a child: dotted when the name reads back as one name, else in
     * brackets, which keep dots, and refused when even brackets cannot hold it.
     */
    private static String childKey(String prefix, String name) {
        // An empty name ends the key, or sits between two dots: "a." and "a..b" read back as it.
        if (name.isEmpty()) return prefix + ".";
        return isPlainName(name) ? prefix + "." + name : bracketed(name, prefix);
    }

    private static boolean isPlainName(String name) {
        return !name.isEmpty() && name.indexOf('.') < 0 && name.indexOf('[') < 0 && name.indexOf(']') < 0;
    }

    private static String bracketed(String name, String prefix) {
        if (name.indexOf(']') >= 0)
            throw new IllegalArgumentException("The key \"" + name + "\" has no properties form: a name holding "
                  + "a ] cannot be written as one part of a key.");
        return prefix + "[" + name + "]";
    }

    /**
     * The escaping {@code Properties.store} applies to keys, written to a
     * stream: everything outside printable ASCII as {@code \}{@code uXXXX}.
     * Spring Boot and Gradle read properties files as ISO-8859-1, so a
     * {@code é} saved as UTF-8 came back to them as two other characters.
     */
    static String escapeKey(String key) {
        return escape(key, true);
    }

    /** As {@link #escapeKey}, for values: a leading space, and what a line cannot hold. */
    static String escapeValue(String value) {
        return escape(value, false);
    }

    /** A key named in a comment: a line break in it would end the comment and start an entry. */
    private static String escapeComment(String key) {
        return escape(key, false).replace("\\ ", " ");
    }

    private static String escape(String s, boolean isKey) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                // Leading whitespace of a value is skipped on reading, and any in a key ends it.
                case ' ' -> out.append(isKey || i == 0 ? "\\ " : " ");
                case '=', ':' -> out.append(isKey ? "\\" + c : String.valueOf(c));
                // Only a line's first character opens a comment.
                case '#', '!' -> out.append(isKey && i == 0 ? "\\" + c : String.valueOf(c));
                default -> {
                    if (c < 0x20 || c > 0x7E) out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    // ── Format ───────────────────────────────────────────────────────────

    /**
     * The Format action: every entry as {@code key=value} on one line,
     * comments kept, and one blank line wherever the file had any. Keys and
     * values keep the text they were written with, escapes and all, so a file
     * of UTF-8 text stays readable and one of {@code \}{@code uXXXX} escapes
     * stays safe for an ISO-8859-1 reader.
     *
     * @param sortKeys entries sorted by key, each taking the comments directly
     *                 above it along; a header comment block, set off by a
     *                 blank line, stays at the top and trailing comments at
     *                 the end
     */
    String format(String text, boolean sortKeys) {
        List<Line> lines = lines(TextDecoder.stripBom(text));
        int first = 0;
        while (first < lines.size() && lines.get(first).isComment()) first++;
        // Joined, not terminated: Format ends on the last line, as for every other format.
        List<String> out = new ArrayList<>();
        if (!sortKeys || first == lines.size()) {
            for (Line line : lines) {
                if (line.blankBefore()) out.add("");
                out.add(render(line));
            }
            return String.join("\n", out);
        }
        // The header: the comments above the last blank line before the first entry.
        int header = 0;
        for (int k = first; k > 0; k--) {
            if (lines.get(k).blankBefore()) {
                header = k;
                break;
            }
        }
        for (int k = 0; k < header; k++) {
            if (lines.get(k).blankBefore()) out.add("");
            out.add(render(lines.get(k)));
        }
        if (header > 0) out.add("");
        // Groups: an entry and the comments directly above it.
        List<List<Line>> groups = new ArrayList<>();
        List<Line> pending = new ArrayList<>();
        for (int k = header; k < lines.size(); k++) {
            pending.add(lines.get(k));
            if (!lines.get(k).isComment()) {
                groups.add(pending);
                pending = new ArrayList<>();
            }
        }
        // Stable: a key written twice keeps its two lines in order, so the last still wins.
        groups.sort(Comparator.comparing(group -> group.getLast().key()));
        for (List<Line> group : groups) for (Line line : group) out.add(render(line));
        if (!pending.isEmpty()) {
            if (pending.getFirst().blankBefore()) out.add("");
            for (Line line : pending) out.add(render(line));
        }
        return String.join("\n", out);
    }

    /**
     * A line as Format writes it. The raw key ends where an unescaped
     * separator or blank ended it, and the raw value starts past the blanks
     * after the separator, so both read back as they did, behind a plain {@code =}.
     */
    private static String render(Line line) {
        return line.isComment() ? line.comment().stripTrailing() : line.rawKey() + "=" + line.rawValue();
    }
}
