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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads a protobuf string option, including adjacent literals and byte escapes. */
final class ProtoStringLiteral {
    private ProtoStringLiteral() {}

    static String read(String source, int start) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = trivia(source, start);
        boolean found = false;
        while (i < source.length() && (source.charAt(i) == '"' || source.charAt(i) == '\'')) {
            found = true;
            char quote = source.charAt(i++);
            boolean closed = false;
            while (i < source.length()) {
                int code = source.codePointAt(i);
                i += Character.charCount(code);
                if (code == quote) { closed = true; break; }
                if (code == '\n' || code == '\r' || code == 0) throw invalid();
                if (code != '\\') {
                    bytes.writeBytes(new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (i == source.length()) throw invalid();
                char escape = source.charAt(i++);
                switch (escape) {
                    case 'a' -> bytes.write(7);
                    case 'b' -> bytes.write(8);
                    case 'f' -> bytes.write(12);
                    case 'n' -> bytes.write(10);
                    case 'r' -> bytes.write(13);
                    case 't' -> bytes.write(9);
                    case 'v' -> bytes.write(11);
                    case '\\', '\'', '"' -> bytes.write(escape);
                    default -> {
                        int radix, min, max;
                        boolean unicode = escape == 'u' || escape == 'U';
                        if (unicode) { radix = 16; min = max = escape == 'u' ? 4 : 8; }
                        else if (escape == 'x' || escape == 'X') { radix = 16; min = 1; max = 2; }
                        else if (escape >= '0' && escape <= '7') { radix = 8; min = 1; max = 3; i--; }
                        else throw invalid();
                        long value = 0;
                        int digits = 0;
                        while (i < source.length() && digits < max) {
                            int digit = Character.digit(source.charAt(i), radix);
                            if (digit < 0) break;
                            value = value * radix + digit;
                            i++;
                            digits++;
                        }
                        if (digits < min) throw invalid();
                        if (unicode) {
                            if (value > Character.MAX_CODE_POINT || value >= 0xD800 && value <= 0xDFFF)
                                throw invalid();
                            bytes.writeBytes(new String(Character.toChars((int) value)).getBytes(StandardCharsets.UTF_8));
                        } else {
                            if (value > 255) throw invalid();
                            bytes.write((int) value);
                        }
                    }
                }
            }
            if (!closed) throw invalid();
            i = trivia(source, i);
        }
        if (!found || i == source.length() || source.charAt(i) != ',' && source.charAt(i) != ']')
            throw invalid();
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
            throw invalid();
        }
    }

    private static int trivia(String source, int i) {
        while (i < source.length()) {
            if (Character.isWhitespace(source.charAt(i))) { i++; continue; }
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i + 2);
                i = end < 0 ? source.length() : end + 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) throw invalid();
                i = end + 2;
            } else break;
        }
        return i;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid json_name string literal");
    }
}
