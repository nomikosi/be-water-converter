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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Repairs truncated JSON for the lenient reader, and counts the comments a
 * rewrite of it would lose. Knows the same syntax the reader accepts:
 * comments and single-quoted strings included.
 */
final class JsonRepair {

    private JsonRepair() {}

    /**
     * Leniently repairs truncated JSON: closes a dangling escape, an
     * unterminated string, and any unclosed {@code {} / []} brackets.
     * Only applied when the input format is JSON.
     */
    static String autoClose(String json) {
        Scan scan = scan(json);
        // An unterminated comment needs no closer, and appending one inside it
        // would be appending to a comment.
        if (scan.unterminatedBlockComment()) return json;
        if (scan.closers().isEmpty()) return json;
        // A line comment runs to the end of its line, so a closer appended on
        // the same line was part of the comment: {"a":1 // note} still failed
        // with "expected close marker". The closers start a line of their own.
        return json + (scan.inLineComment() ? "\n" : "") + scan.closers();
    }

    /** What one pass over JSON text finds: the closers it lacks, and the comments it carries. */
    private record Scan(String closers, boolean unterminatedBlockComment, boolean inLineComment,
          int comments) {}

    private static Scan scan(String json) {
        Deque<Character> stack = new ArrayDeque<>();
        char quote       = 0;          // 0 = not in a string, else the opening quote
        boolean escape   = false;
        // The reader accepts comments and single quotes, so the scan has to know
        // about them too: a brace inside // a note, or inside 'it {', was counted
        // as real and this appended a closer that made valid input fail to parse.
        boolean lineComment = false, blockComment = false;
        int comments = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
            if (lineComment)  { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (escape)       { escape = false; continue; }
            if (quote != 0) {
                if (c == '\\')      escape = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'')            { quote = c; continue; }
            if (c == '/' && next == '/')          { lineComment = true; comments++; i++; continue; }
            if (c == '#')                         { lineComment = true; comments++; continue; }
            if (c == '/' && next == '*')          { blockComment = true; comments++; i++; continue; }
            if (c == '{')                          stack.push('}');
            else if (c == '[')                     stack.push(']');
            else if (c == '}' || c == ']')       { if (!stack.isEmpty()) stack.pop(); }
        }
        StringBuilder closers = new StringBuilder();
        if (escape)     closers.append('\\');
        if (quote != 0) closers.append(quote);
        while (!stack.isEmpty()) closers.append(stack.pop());
        return new Scan(closers.toString(), blockComment, lineComment, comments);
    }

    /** How many comments the text carries: what a Format through the JSON tree would drop. */
    static int countComments(String json) {
        return scan(json).comments();
    }
}
