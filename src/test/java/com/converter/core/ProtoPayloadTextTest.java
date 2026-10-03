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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Protobuf payload text")
class ProtoPayloadTextTest {

    private static final byte[] EXAMPLE = {0x08, (byte) 0x96, 0x01};

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"089601", "08 96 01", "08\n96\r\n01", "0x089601", "0x08 0x96 0x01", "0x08,0x96,0x01",
          "\\x08\\x96\\x01", "08:96:01", "08-96-01", "08, 96, 01", "  08 96 01  ", "0X08 0X96 0X01", "\\X08\\X96\\X01"})
    @DisplayName("hex, however it is spaced, prefixed or separated")
    void hexForms(String text) {
        assertThat(ProtoPayloadText.bytes(text)).containsExactly(EXAMPLE);
    }

    @Test @DisplayName("hex digits in either case")
    void hexCase() {
        assertThat(ProtoPayloadText.bytes("CAFEbabe")).containsExactly(0xCA, 0xFE, 0xBA, 0xBE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CJYB", "CJYB\n", "CJ YB", "CJYB=="})
    @DisplayName("base64, padded or not, spaced or not")
    void base64Forms(String text) {
        assertThat(ProtoPayloadText.bytes(text)).containsExactly(EXAMPLE);
    }

    @Test @DisplayName("standard and URL-safe base64, every byte value, every length")
    void base64Alphabets() {
        Random random = new Random(42);
        for (int length = 1; length < 64; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            String standard = Base64.getEncoder().encodeToString(bytes);
            String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            // Text of hex digits alone reads as hex, so those encodings are skipped.
            if (!standard.matches("[0-9a-fA-F=]+"))
                assertThat(ProtoPayloadText.bytes(standard)).as(standard).containsExactly(bytes);
            if (!urlSafe.matches("[0-9a-fA-F]+"))
                assertThat(ProtoPayloadText.bytes(urlSafe)).as(urlSafe).containsExactly(bytes);
        }
    }

    @Test @DisplayName("every byte value round-trips through hex")
    void allByteValues() {
        StringBuilder hex = new StringBuilder();
        byte[] expected = new byte[256];
        for (int b = 0; b < 256; b++) {
            hex.append(String.format("%02x ", b));
            expected[b] = (byte) b;
        }
        assertThat(ProtoPayloadText.bytes(hex.toString())).containsExactly(expected);
    }

    @Test @DisplayName("text of hex digits alone reads as hex, even when it is also base64")
    void hexWins() {
        // "CAFE" is valid base64 too (08 01 45); a payload of hex digits means hex.
        assertThat(ProtoPayloadText.bytes("CAFE")).containsExactly(0xCA, 0xFE);
    }

    @Test @DisplayName("a paste reads as a payload as far as it goes, cut mid-byte or not")
    void readsAsPayload() {
        assertThat(ProtoPayloadText.readsAsPayload("0x08, 0x96, 0x01,\n0x12, 0x07, 0x74,\n")).isTrue();
        assertThat(ProtoPayloadText.readsAsPayload("08 96 0")).isTrue();
        assertThat(ProtoPayloadText.readsAsPayload("CJYBEgd0ZXN0aW5n")).isTrue();
        assertThat(ProtoPayloadText.readsAsPayload("CJYB_-")).isTrue();
        assertThat(ProtoPayloadText.readsAsPayload("{\"name\": \"Ada\"}")).isFalse();
        assertThat(ProtoPayloadText.readsAsPayload("<name>Ada</name>")).isFalse();
        assertThat(ProtoPayloadText.readsAsPayload("name: Ada\nage: 36\n")).isFalse();
        assertThat(ProtoPayloadText.readsAsPayload("ab+_cd")).isFalse();
        assertThat(ProtoPayloadText.readsAsPayload(" \n")).isFalse();
        assertThat(ProtoPayloadText.readsAsPayload(null)).isFalse();
    }

    @Test @DisplayName("empty text, an odd number of hex digits, and text that is neither are refused")
    void refusals() {
        assertThatThrownBy(() -> ProtoPayloadText.bytes("  \n ")).hasMessageContaining("empty");
        assertThatThrownBy(() -> ProtoPayloadText.bytes(null)).hasMessageContaining("empty");
        // A dropped digit is a typo to report, not base64 to decode instead.
        assertThatThrownBy(() -> ProtoPayloadText.bytes("08 96 0")).hasMessageContaining("odd number of digits (5)");
        assertThatThrownBy(() -> ProtoPayloadText.bytes("not a payload!"))
              .hasMessageContaining("neither hex nor base64");
        assertThatThrownBy(() -> ProtoPayloadText.bytes("Z")).hasMessageContaining("neither hex nor base64");
        // Standard and URL-safe alphabets do not mix.
        assertThatThrownBy(() -> ProtoPayloadText.bytes("ab+_cd")).hasMessageContaining("neither hex nor base64");
    }
}
