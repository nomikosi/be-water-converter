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

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a subtree from the JSON pivot before it is rendered, so a large
 * document can be narrowed to the part actually being converted.
 *
 * <p>Paths are RFC 6901 JSON Pointers, with a dotted convenience syntax on top:
 * {@code users[0].name} and {@code /users/0/name} select the same node. Only
 * selection is supported — this is deliberately not a query language, and adds
 * no dependency beyond the Jackson already in use.
 */
public final class JsonPathFilter {

    private JsonPathFilter() {}

    /**
     * Converts a user-entered path to a JSON Pointer.
     *
     * @throws IllegalArgumentException if the path cannot be parsed
     */
    public static JsonPointer toPointer(String path) {
        // Spaces in a pointer are part of its reference tokens. Only the
        // convenience syntax accepts surrounding whitespace.
        String trimmed = path == null ? "" : path.startsWith("/") ? path : path.trim();
        if (trimmed.isEmpty()) return JsonPointer.empty();

        // Already a pointer.
        if (trimmed.startsWith("/")) {
            try {
                return JsonPointer.compile(trimmed);
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException(
                      "Not a valid JSON Pointer: " + trimmed, malformed);
            }
        }

        // '$' is how JSONPath spells the root; accept and drop it so paths
        // copied from other tools work. Only as the root, though: "$id" and
        // "$defs" are ordinary keys in JSON Schema, including the schemas this
        // plugin generates, and dropping the '$' selected "id" instead of "$id".
        if (trimmed.equals("$") || trimmed.startsWith("$.") || trimmed.startsWith("$["))
            trimmed = trimmed.substring(1);
        if (trimmed.startsWith(".")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) return JsonPointer.empty();

        StringBuilder pointer = new StringBuilder();
        for (String segment : splitSegments(trimmed)) {
            // Escaping per RFC 6901: '~' first, then '/'.
            pointer.append('/')
                  .append(segment.replace("~", "~0").replace("/", "~1"));
        }
        if (pointer.isEmpty()) return JsonPointer.empty();
        return JsonPointer.compile(pointer.toString());
    }

    /** Splits dotted names and bracket tokens, retaining quoted empty names. */
    private static List<String> splitSegments(String path) {
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (i < path.length()) {
            if (path.charAt(i) == '[') {
                int start = ++i;
                while (i < path.length() && Character.isWhitespace(path.charAt(i))) i++;
                if (i == path.length()) throw invalid("Unclosed '['", path);
                char quote = path.charAt(i);
                String token;
                if (quote == '\'' || quote == '"') {
                    StringBuilder value = new StringBuilder();
                    boolean closed = false;
                    i++;
                    while (i < path.length()) {
                        char c = path.charAt(i++);
                        if (c == quote) { closed = true; break; }
                        if (c != '\\') { value.append(c); continue; }
                        if (i == path.length()) throw invalid("Unclosed escape", path);
                        char escaped = path.charAt(i++);
                        switch (escaped) {
                            case '\\', '\'', '"', '/' -> value.append(escaped);
                            case 'b' -> value.append('\b');
                            case 'f' -> value.append('\f');
                            case 'n' -> value.append('\n');
                            case 'r' -> value.append('\r');
                            case 't' -> value.append('\t');
                            case 'u' -> {
                                if (i + 4 > path.length()) throw invalid("Incomplete Unicode escape", path);
                                int code = 0;
                                for (int end = i + 4; i < end; i++) {
                                    int digit = Character.digit(path.charAt(i), 16);
                                    if (digit < 0) throw invalid("Invalid Unicode escape", path);
                                    code = code * 16 + digit;
                                }
                                value.append((char) code);
                            }
                            default -> throw invalid("Invalid escape", path);
                        }
                    }
                    if (!closed) throw invalid("Unclosed quoted key", path);
                    token = value.toString();
                    while (i < path.length() && Character.isWhitespace(path.charAt(i))) i++;
                } else {
                    while (i < path.length() && path.charAt(i) != ']') {
                        if (path.charAt(i) == '[') throw invalid("Unexpected '['", path);
                        i++;
                    }
                    token = path.substring(start, i).trim();
                    if (token.isEmpty()) throw invalid("Empty []", path);
                }
                if (i == path.length() || path.charAt(i) != ']')
                    throw invalid("Unclosed '[' or unexpected text after quoted key", path);
                i++;
                segments.add(token);
            } else {
                int start = i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    if (path.charAt(i) == ']') throw invalid("Unexpected ']'", path);
                    i++;
                }
                if (start == i) throw invalid("Empty dotted segment", path);
                segments.add(path.substring(start, i));
            }
            if (i == path.length()) break;
            if (path.charAt(i) == '.') {
                if (++i == path.length()) throw invalid("Trailing '.'", path);
            } else if (path.charAt(i) != '[') {
                throw invalid("Expected '.' or '['", path);
            }
        }
        return segments;
    }

    private static IllegalArgumentException invalid(String reason, String path) {
        return new IllegalArgumentException(reason + " in path: " + path);
    }

    /**
     * Applies a path to a tree.
     *
     * @throws IllegalArgumentException when the path matches nothing, so an
     *         empty result is reported rather than silently converting {@code null}
     */
    public static JsonNode apply(JsonNode root, String path) {
        JsonPointer pointer = toPointer(path);
        if (pointer.matches()) return root;               // empty path: whole document
        JsonNode selected = root.at(pointer);
        if (selected.isMissingNode()) {
            throw new IllegalArgumentException("Path matched nothing: " + path);
        }
        return selected;
    }
}
