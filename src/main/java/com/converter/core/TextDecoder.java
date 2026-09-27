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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns the bytes of a file into text, honouring a byte-order mark.
 *
 * <p>Files were read as UTF-8 with a Latin-1 fallback for anything that was
 * not. A UTF-16 file — what Notepad's "Unicode" and many Windows exports write
 * — is not UTF-8 either, so it fell to Latin-1 and opened as
 * {@code þÿ\0{\0"…}: every character interleaved with a NUL, which then failed
 * to parse with an error about a character the user never typed. The mark at
 * the front says exactly what the file is.
 *
 * <p>Deliberately free of {@code com.intellij} imports, so the rule is covered
 * by the fast unit tests.
 */
public final class TextDecoder {

    private TextDecoder() {}

    /**
     * @param text     the decoded text, without its byte-order mark
     * @param charset  the charset used
     * @param fallback true when nothing identified the encoding and the charset
     *                 was guessed — Windows-1252 when the bytes fit it, else
     *                 ISO-8859-1, which maps every byte so the file at least
     *                 opens; the caller should say so
     */
    public record Decoded(String text, Charset charset, boolean fallback) {}

    public static Decoded decode(byte[] bytes) {
        return decode(bytes, null);
    }

    /**
     * Decodes by the byte-order mark when there is one, then by
     * {@code preferred} — the charset the IDE has for the file — when the bytes
     * are valid in it, then as UTF-8.
     */
    public static Decoded decode(byte[] bytes, Charset preferred) {
        Mark mark = markOf(bytes);
        Decoded decoded = mark != null ? strict(bytes, mark.length(), mark.charset())
              : preferred == null ? null : strict(bytes, 0, preferred);
        if (decoded == null) decoded = strict(bytes, 0, StandardCharsets.UTF_8);
        if (decoded != null) return decoded;
        // A mark that lies, or no mark and not UTF-8. Windows-1252 first: it is
        // what Excel and Notepad write on Western Windows, and read as
        // ISO-8859-1 its euro sign, curly quotes, dashes and ellipsis became
        // invisible C1 control characters — U+0085 is even a line break to
        // YAML. It leaves five bytes undefined, and a file using them is not
        // Windows-1252; Latin-1 maps every byte, so that file still opens. The
        // status line says which encoding was used.
        Decoded windows = strict(bytes, 0, WINDOWS_1252);
        if (windows != null) return new Decoded(windows.text(), WINDOWS_1252, true);
        return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1),
              StandardCharsets.ISO_8859_1, true);
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** A byte-order mark: the charset it names, and how many bytes it takes. */
    public record Mark(Charset charset, int length) {}

    /** The byte-order mark {@code bytes} start with, or null. */
    public static Mark markOf(byte[] bytes) {
        int n = bytes.length;
        // The 4-byte marks before the 2-byte ones: UTF-32LE starts with the
        // UTF-16LE mark.
        if (n >= 4 && at(bytes, 0xFF, 0xFE, 0x00, 0x00)) return new Mark(Charset.forName("UTF-32LE"), 4);
        if (n >= 4 && at(bytes, 0x00, 0x00, 0xFE, 0xFF)) return new Mark(Charset.forName("UTF-32BE"), 4);
        if (n >= 3 && at(bytes, 0xEF, 0xBB, 0xBF)) return new Mark(StandardCharsets.UTF_8, 3);
        if (n >= 2 && at(bytes, 0xFF, 0xFE)) return new Mark(StandardCharsets.UTF_16LE, 2);
        if (n >= 2 && at(bytes, 0xFE, 0xFF)) return new Mark(StandardCharsets.UTF_16BE, 2);
        return null;
    }

    private static boolean at(byte[] bytes, int... expected) {
        for (int i = 0; i < expected.length; i++)
            if ((bytes[i] & 0xFF) != expected[i]) return false;
        return true;
    }

    /**
     * Decodes from {@code offset} on, refusing rather than substituting: a
     * file that is not what its mark says is not silently read as something
     * else. Null when the bytes do not decode.
     */
    private static Decoded strict(byte[] bytes, int offset, Charset charset) {
        try {
            String text = charset.newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                  .toString();
            return new Decoded(text, charset, false);
        } catch (CharacterCodingException notThisCharset) {
            return null;
        }
    }

    /**
     * Strips a leading UTF-8 BOM. Excel writes one on "CSV UTF-8" export, and
     * nothing downstream treats U+FEFF as whitespace: it silently became part of
     * the first CSV header name, and made JSON, TOML and XML fail to parse.
     */
    public static String stripBom(String text) {
        // The escape, not a literal U+FEFF char: the character is invisible, so
        // a literal survives review poorly and dies silently if the file is
        // ever re-encoded.
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF'
              ? text.substring(1) : text;
    }
}
