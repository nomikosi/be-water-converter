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
     * @param fallback true when nothing identified the encoding and the bytes
     *                 were mapped one-to-one as ISO-8859-1 so the file at least
     *                 opens; the caller should say so
     */
    public record Decoded(String text, Charset charset, boolean fallback) {}

    public static Decoded decode(byte[] bytes) {
        int n = bytes.length;
        // The 4-byte marks before the 2-byte ones: UTF-32LE starts with the
        // UTF-16LE mark.
        Decoded decoded;
        if (n >= 4 && at(bytes, 0xFF, 0xFE, 0x00, 0x00))
            decoded = strict(bytes, 4, Charset.forName("UTF-32LE"));
        else if (n >= 4 && at(bytes, 0x00, 0x00, 0xFE, 0xFF))
            decoded = strict(bytes, 4, Charset.forName("UTF-32BE"));
        else if (n >= 3 && at(bytes, 0xEF, 0xBB, 0xBF))
            decoded = strict(bytes, 3, StandardCharsets.UTF_8);
        else if (n >= 2 && at(bytes, 0xFF, 0xFE))
            decoded = strict(bytes, 2, StandardCharsets.UTF_16LE);
        else if (n >= 2 && at(bytes, 0xFE, 0xFF))
            decoded = strict(bytes, 2, StandardCharsets.UTF_16BE);
        else
            decoded = strict(bytes, 0, StandardCharsets.UTF_8);
        if (decoded != null) return decoded;
        // A mark that lies, or no mark and not UTF-8: Latin-1 maps every byte,
        // so the file opens, and the status line says which encoding was used.
        return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1),
              StandardCharsets.ISO_8859_1, true);
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
}
