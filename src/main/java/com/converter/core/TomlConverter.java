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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

public class TomlConverter {
    private final ObjectMapper jsonMapper;
    private final TomlMapper   tomlMapper;

    public TomlConverter() {
        // No INDENT_OUTPUT: this mapper only ever writes the internal JSON
        // pivot, which the next stage re-parses and nobody reads. Indenting it
        // measured 1.29-1.45x the compact size for no benefit.
        jsonMapper = PivotJson.mapper();
        // The TOML parser already reads floats as BigDecimal; it was the default
        // node factory's stripTrailingZeros that turned 1.10 into 1.1, and
        // Format then wrote that over the document. Same fix as the JSON reader.
        tomlMapper = TomlMapper.builder().nodeFactory(new JsonNodeFactory(true)).build();
    }

    /** A whole decimal value token; key and container context is tracked separately. */
    private static final java.util.regex.Pattern DECIMAL_INTEGER =
          java.util.regex.Pattern.compile("([+-]?)(\\d[\\d_]*)");

    /**
     * Digit count at which jackson-dataformat-toml mis-reads a decimal integer.
     * Measured across 2.17.2, 2.18.2, 2.19.0 and 2.21.1 — all identical, so this
     * is a long-standing upstream defect rather than a regression to wait out.
     */
    private static final int BROKEN_DIGITS = 19;

    public String tomlToJson(String toml) throws Exception {
        if (toml == null || toml.isBlank())
            throw new IllegalArgumentException("Input TOML must not be empty");
        forEachValueToken(toml, TomlConverter::rejectUnreadableInteger);
        JsonNode node = tomlMapper.readTree(toml);
        return jsonMapper.writeValueAsString(node);
    }

    /**
     * Visits unquoted value tokens for conversion and formatting safeguards.
     *
     * <p>It returns them silently corrupted, which is the one outcome this
     * project will not ship: {@code id = 1723600000000000000} parses as
     * {@code 0}, {@code 9223372036854775807} as {@code 6854775807}, and a
     * negative literal of 20+ digits comes back positive. Exactly 19 digits is
     * the boundary where a value may or may not fit a {@code long}, and 19-digit
     * ids are ordinary — a Discord or Twitter snowflake is exactly that.
     *
     * <p>Hex, float and quoted forms are read correctly, so only bare decimal
     * integers are checked.
     */
    static void forEachValueToken(String toml, java.util.function.Consumer<String> consumer) {
        String scannable = maskStringsAndComments(toml);
        java.util.Deque<Character> containers = new java.util.ArrayDeque<>();
        boolean readingKey = true;
        for (int i = 0; i < scannable.length();) {
            char c = scannable.charAt(i);
            if (Character.isWhitespace(c)) {
                if (c == '\n' && containers.isEmpty()) readingKey = true;
                i++;
            } else if (c == '[' && readingKey && containers.isEmpty()) {
                // A table header contains keys, even when they consist of digits.
                int end = scannable.indexOf('\n', i);
                i = end < 0 ? scannable.length() : end + 1;
            } else if (c == '[' || c == '{') {
                containers.push(c);
                readingKey = c == '{';
                i++;
            } else if (c == ']' || c == '}') {
                if (!containers.isEmpty()) containers.pop();
                readingKey = false;
                i++;
            } else if (c == '=') {
                readingKey = false;
                i++;
            } else if (c == ',') {
                readingKey = !containers.isEmpty() && containers.peek() == '{';
                i++;
            } else {
                int start = i++;
                while (i < scannable.length() && !Character.isWhitespace(scannable.charAt(i))
                      && "[]=,{}".indexOf(scannable.charAt(i)) < 0) i++;
                if (!readingKey) consumer.accept(scannable.substring(start, i));
            }
        }
    }

    private static void rejectUnreadableInteger(String token) {
        java.util.regex.Matcher m = DECIMAL_INTEGER.matcher(token);
        if (!m.matches()) return;
        boolean negative = "-".equals(m.group(1));
        int digits = m.group(2).replace("_", "").replaceFirst("^0+(?=\\d)", "").length();
        if (digits == BROKEN_DIGITS || (negative && digits > BROKEN_DIGITS)) {
            throw new IllegalArgumentException(
                  "TOML integer " + token + " cannot be read correctly: the bundled TOML "
                  + "parser mis-parses decimal integers of " + BROKEN_DIGITS + " digits and "
                  + "returns a different number, so converting would silently change your "
                  + "data. Quote it (\"" + token + "\") to carry it through as text, or "
                  + "write it in hexadecimal.");
        }
    }

    /**
     * Blanks comments and quoted strings so the integer scan cannot fire on a
     * digit run inside them. Length is preserved so match offsets stay usable.
     */
    static String maskStringsAndComments(String toml) {
        char[] out = toml.toCharArray();
        int i = 0, n = toml.length();
        while (i < n) {
            char c = toml.charAt(i);
            if (c == '#') {
                while (i < n && toml.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                int stop = endOfString(toml, i);
                while (i < stop) {
                    out[i] = (toml.charAt(i) == '\n') ? '\n' : ' ';   // keep line structure
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * How many comments the document carries — what Format, which keeps only
     * the values, would discard. A {@code #} inside a string is not a comment
     * and a second {@code #} inside a comment is not another one.
     */
    static int countComments(String toml) {
        int count = 0, i = 0, n = toml.length();
        while (i < n) {
            char c = toml.charAt(i);
            if (c == '#') {
                count++;
                while (i < n && toml.charAt(i) != '\n') i++;
            } else if (c == '"' || c == '\'') {
                i = endOfString(toml, i);
            } else {
                i++;
            }
        }
        return count;
    }

    /**
     * The index just past the string literal that opens at {@code i}, or the end
     * of the input when it is never closed. Handles basic, literal and both
     * triple-quoted forms; basic strings honour backslash escapes, literal ones
     * do not.
     */
    private static int endOfString(String toml, int i) {
        char c = toml.charAt(i);
        int n = toml.length();
        boolean triple = i + 2 < n && toml.charAt(i + 1) == c && toml.charAt(i + 2) == c;
        String close = triple ? String.valueOf(new char[]{c, c, c}) : String.valueOf(c);
        int from = i + close.length();
        int end = toml.indexOf(close, from);
        while (c == '"' && end > 0 && countTrailingBackslashes(toml, end) % 2 == 1)
            end = toml.indexOf(close, end + 1);
        if (end < 0) end = n - close.length();          // unterminated: the rest is string
        // A multi-line string may end with one or two quote characters right
        // before its delimiter ("""they said "hi"""" is legal TOML), so the
        // closing delimiter is the LAST three of the run. Taking the first three
        // left a stray quote that opened a phantom string over the rest of the
        // document, and a 19-digit integer behind it slipped past the guard.
        if (triple)
            for (int extra = 0; extra < 2 && end + close.length() < n
                  && toml.charAt(end + close.length()) == c; extra++) end++;
        return Math.min(n, end + close.length());
    }

    private static int countTrailingBackslashes(String s, int index) {
        int count = 0;
        for (int i = index - 1; i >= 0 && s.charAt(i) == '\\'; i--) count++;
        return count;
    }

    public String jsonToToml(String json) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input JSON must not be empty");
        JsonNode node = jsonMapper.readTree(json);

        // TOML documents are tables: a bare array or scalar root would render
        // as a key-value pair with an EMPTY key (" = [...]"), which is invalid
        // TOML. Wrap them under a named key, mirroring the XML converter.
        if (node.isArray()) {
            node = jsonMapper.createObjectNode().set("items", node);
        } else if (!node.isObject()) {
            node = jsonMapper.createObjectNode().set("value", node);
        }

        // An empty table serialises to " = {}", which is not valid TOML — and
        // Format turned a comment-only file into exactly that. A comment round
        // trips, whereas "" does not: tomlToJson rejects blank input.
        if (node.isEmpty()) return "# empty document\n";

        return tomlMapper.writeValueAsString(node);
    }
}
