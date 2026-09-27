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
}
