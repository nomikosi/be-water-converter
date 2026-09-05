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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** A file's byte-order mark says what it is; the reader has to listen. */
@DisplayName("Text decoding")
class TextDecoderTest {

    private static final String DOC = "{\"a\": \"café – 日本\"}";

    @Test @DisplayName("UTF-16 and UTF-32 files are read by their mark, without it")
    void marksAreHonoured() {
        // Read as UTF-8 with a Latin-1 fallback, a UTF-16 file opened as
        // NUL-interleaved garbage starting with the two mark bytes.
        for (String name : new String[]{"UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"}) {
            Charset charset = Charset.forName(name);
            // The mark spelled by code point: a literal U+FEFF is invisible in source.
            byte[] bytes = concat(new String(Character.toChars(0xFEFF)).getBytes(charset),
                  DOC.getBytes(charset));
            TextDecoder.Decoded decoded = TextDecoder.decode(bytes);
            assertThat(decoded.text()).describedAs(name).isEqualTo(DOC);
            assertThat(decoded.charset()).describedAs(name).isEqualTo(charset);
            assertThat(decoded.fallback()).describedAs(name).isFalse();
        }
    }

    @Test @DisplayName("UTF-8 with and without a mark reads as before, mark removed")
    void utf8IsTheDefault() {
        assertThat(TextDecoder.decode(DOC.getBytes(StandardCharsets.UTF_8)))
              .isEqualTo(new TextDecoder.Decoded(DOC, StandardCharsets.UTF_8, false));
        byte[] marked = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, DOC.getBytes(StandardCharsets.UTF_8));
        assertThat(TextDecoder.decode(marked))
              .isEqualTo(new TextDecoder.Decoded(DOC, StandardCharsets.UTF_8, false));
        assertThat(TextDecoder.decode(new byte[0]))
              .isEqualTo(new TextDecoder.Decoded("", StandardCharsets.UTF_8, false));
    }

    @Test @DisplayName("bytes that are no known encoding fall back to Latin-1, and say so")
    void latin1Fallback() {
        byte[] cp1252 = "café".getBytes(StandardCharsets.ISO_8859_1);
        TextDecoder.Decoded decoded = TextDecoder.decode(cp1252);
        assertThat(decoded.text()).isEqualTo("café");
        assertThat(decoded.fallback()).isTrue();
    }

    @Test @DisplayName("a mark that lies is not silently read as something else")
    void invalidUtf16FallsBack() {
        // An odd byte count cannot be UTF-16: the mark is wrong, and the bytes
        // go through the fallback rather than a decoder that would substitute.
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, 'a'};
        assertThat(TextDecoder.decode(bytes).fallback()).isTrue();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
