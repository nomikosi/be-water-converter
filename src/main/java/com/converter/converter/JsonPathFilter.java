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
        String trimmed = path == null ? "" : path.trim();
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
        // copied from other tools work.
        if (trimmed.startsWith("$")) trimmed = trimmed.substring(1);
        if (trimmed.startsWith(".")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) return JsonPointer.empty();

        StringBuilder pointer = new StringBuilder();
        for (String segment : splitSegments(trimmed)) {
            if (segment.isEmpty()) continue;
            // Escaping per RFC 6901: '~' first, then '/'.
            pointer.append('/')
                  .append(segment.replace("~", "~0").replace("/", "~1"));
        }
        if (pointer.isEmpty()) return JsonPointer.empty();
        return JsonPointer.compile(pointer.toString());
    }

    /** Splits {@code a.b[0].c} into a, b, 0, c. */
    private static List<String> splitSegments(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                segments.add(current.toString());
                current.setLength(0);
            } else if (c == '[') {
                segments.add(current.toString());
                current.setLength(0);
                int close = path.indexOf(']', i);
                if (close < 0) throw new IllegalArgumentException(
                      "Unclosed '[' in path: " + path);
                String index = path.substring(i + 1, close).trim();
                // Bracket-quoted keys ("with.dot") as well as array indices.
                if (index.length() >= 2
                      && (index.startsWith("'") && index.endsWith("'")
                          || index.startsWith("\"") && index.endsWith("\""))) {
                    index = index.substring(1, index.length() - 1);
                }
                if (index.isEmpty()) throw new IllegalArgumentException(
                      "Empty [] in path: " + path);
                segments.add(index);
                i = close;
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
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
