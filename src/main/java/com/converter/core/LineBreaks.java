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

/**
 * A document's line breaks. Output is written with LF; a document read from a
 * Windows file keeps CRLF, and so does a file saved over one.
 */
public final class LineBreaks {

    private LineBreaks() {}

    /** The separator a text's first line ends with: "\r\n" or "\n", or null when it has one line. */
    public static String of(CharSequence text) {
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == '\n') return i > 0 && text.charAt(i - 1) == '\r' ? "\r\n" : "\n";
        return null;
    }

    /** The text with every line break, LF or CRLF, written as {@code separator}. */
    public static String convert(String text, String separator) {
        String lf = text.replace("\r\n", "\n");
        return "\n".equals(separator) ? lf : lf.replace("\n", separator);
    }

    /** The first CSV row separator, ignoring line breaks inside quoted cells (including headers). */
    static String ofCsv(CharSequence text, char quote) {
        int at = nextCsvLineBreak(text, 0, quote);
        if (at < 0) return null;
        if (text.charAt(at) == '\n') return "\n";
        return at + 1 < text.length() && text.charAt(at + 1) == '\n' ? "\r\n" : "\r";
    }

    /** Converts only CSV row separators; every character inside quoted cells stays as written. */
    static String convertCsv(String text, String separator, char quote) {
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        for (int at; (at = nextCsvLineBreak(text, start, quote)) >= 0; ) {
            out.append(text, start, at).append(separator);
            start = at + 1;
            if (text.charAt(at) == '\r' && start < text.length() && text.charAt(start) == '\n') start++;
        }
        return out.append(text, start, text.length()).toString();
    }

    /** Scans from a row's start. Doubled quotes toggle twice, keeping a quoted cell open. */
    private static int nextCsvLineBreak(CharSequence text, int start, char quote) {
        boolean quoted = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) quoted = !quoted;
            else if (!quoted && (c == '\r' || c == '\n')) return i;
        }
        return -1;
    }
}
