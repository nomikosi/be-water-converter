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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Turns saved text into bytes in the style of the file it replaces: its line
 * breaks and, where the format allows another encoding, its encoding and
 * byte-order mark.
 *
 * <p>Every save wrote UTF-8 without a mark. Saved over a Windows-1252 CSV,
 * that turned é into Ã© in Excel, which reads a CSV without a mark in the
 * system's code page; saved over Excel's "CSV UTF-8", it dropped the mark
 * Excel needs to read the file as UTF-8 at all.
 *
 * <p>Deliberately free of {@code com.intellij} imports, like {@link TextDecoder}.
 */
public final class TextEncoder {

    private TextEncoder() {}

    /**
     * The outputs that may keep another encoding. JSON, YAML and TOML are
     * UTF-8 by their specifications, the XML written here declares UTF-8, and
     * protoc reads a .proto file as UTF-8.
     */
    private static final Set<String> KEEP_FILE_ENCODING =
          Set.of(Formats.FMT_CSV, Formats.FMT_JAVA, Formats.FMT_KOTLIN);

    /**
     * @param bytes   what to write
     * @param charset the encoding they are in
     * @param refused the replaced file's encoding when it could not hold the
     *                text, so UTF-8 was written instead, else null; the caller
     *                should say so
     */
    public record Encoded(byte[] bytes, Charset charset, Charset refused) {}

    /**
     * @param head             the start of the file being replaced, or null
     *                         when there is no such file
     * @param ideCharset       the encoding the IDE reads that file in, or null
     * @param newFileSeparator the line separator a new file gets
     */
    public static Encoded forSave(String text, String format, byte[] head, Charset ideCharset,
          String newFileSeparator) {
        boolean csv = Formats.FMT_CSV.equals(format);
        if (head == null) return utf8(convertLineBreaks(text, newFileSeparator, csv), null);
        TextDecoder.Mark mark = TextDecoder.markOf(head);
        int skip = mark == null ? 0 : mark.length();
        Charset existing = mark != null ? mark.charset() : ideCharset != null ? ideCharset : StandardCharsets.UTF_8;
        // Read in the file's own encoding: as bytes, UTF-16's CRLF is \r\0\n\0,
        // whose \n follows a \0 rather than the \r.
        String previous = new String(head, skip, head.length - skip, existing);
        String separator = csv ? LineBreaks.ofCsv(previous, '"') : LineBreaks.of(previous);
        String converted = convertLineBreaks(text, separator != null ? separator : newFileSeparator, csv);
        if (!KEEP_FILE_ENCODING.contains(format)) return utf8(converted, null);
        if (!existing.canEncode() || !existing.newEncoder().canEncode(converted)) return utf8(converted, existing);
        byte[] body = converted.getBytes(existing);
        if (mark == null) return new Encoded(body, existing, null);
        byte[] bytes = Arrays.copyOf(head, skip + body.length);
        System.arraycopy(body, 0, bytes, skip, body.length);
        return new Encoded(bytes, existing, null);
    }

    private static String convertLineBreaks(String text, String separator, boolean csv) {
        // Saved CSV is generated with double quotes for every delimiter the panel offers.
        return csv ? LineBreaks.convertCsv(text, separator, '"') : LineBreaks.convert(text, separator);
    }

    private static Encoded utf8(String text, Charset refused) {
        return new Encoded(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, refused);
    }
}
