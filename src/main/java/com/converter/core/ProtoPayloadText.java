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

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The bytes of a Protobuf payload pasted as text: hex, or base64.
 *
 * <p>Hex may be written as a run of digits ({@code 089601}), spaced
 * ({@code 08 96 01}), with a {@code 0x} or {@code \x} before each byte or the
 * whole run, or separated by colons, commas or dashes, as hex dumps and
 * debuggers print it. Base64 may be standard or URL-safe, padded or not, and
 * broken over lines. Text made only of hex digits is read as hex.
 */
public final class ProtoPayloadText {

    private ProtoPayloadText() {}

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HEX_PREFIX = Pattern.compile("0[xX]|\\\\[xX]");
    private static final Pattern HEX_SEPARATOR = Pattern.compile("[,:;\\-]");
    private static final Pattern HEX_DIGITS = Pattern.compile("[0-9a-fA-F]+");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/\\-_]+={0,2}");

    public static byte[] bytes(String text) {
        String compact = WHITESPACE.matcher(text == null ? "" : text).replaceAll("");
        if (compact.isEmpty())
            throw new IllegalArgumentException("The payload is empty. Paste it as hex, such as 08 96 01, "
                  + "or as base64, such as CJYB.");
        String digits = HEX_SEPARATOR.matcher(HEX_PREFIX.matcher(compact).replaceAll("")).replaceAll("");
        if (!digits.isEmpty() && HEX_DIGITS.matcher(digits).matches()) {
            if (digits.length() % 2 != 0)
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                      "The payload reads as hex but has an odd number of digits (%,d): a byte is two.",
                      digits.length()));
            return hex(digits);
        }
        byte[] decoded = base64(compact);
        if (decoded != null) return decoded;
        throw new IllegalArgumentException("The payload is neither hex nor base64. Paste the bytes as hex, "
              + "such as 08 96 01 or 0x08 0x96 0x01, or as base64, such as CJYB.");
    }

    /**
     * Whether text reads as a payload as far as it goes, hex or base64, its
     * length not judged: a paste is judged by its first part, which may end
     * mid-byte.
     */
    public static boolean readsAsPayload(String text) {
        String compact = WHITESPACE.matcher(text == null ? "" : text).replaceAll("");
        if (compact.isEmpty()) return false;
        String digits = HEX_SEPARATOR.matcher(HEX_PREFIX.matcher(compact).replaceAll("")).replaceAll("");
        if (!digits.isEmpty() && HEX_DIGITS.matcher(digits).matches()) return true;
        if (!BASE64.matcher(compact).matches()) return false;
        boolean urlSafe = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0;
        return !urlSafe || compact.indexOf('+') < 0 && compact.indexOf('/') < 0;
    }

    private static byte[] hex(String digits) {
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(digits, 2 * i, 2 * i + 2, 16);
        return bytes;
    }

    /** Standard or URL-safe base64, padded or not; null when the text is neither. */
    private static byte[] base64(String compact) {
        if (!BASE64.matcher(compact).matches()) return null;
        boolean urlSafe = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0;
        if (urlSafe && (compact.indexOf('+') >= 0 || compact.indexOf('/') >= 0)) return null;
        String unpadded = compact.replace("=", "");
        if (unpadded.length() % 4 == 1) return null;
        try {
            return (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(
                  unpadded + "=".repeat((4 - unpadded.length() % 4) % 4));
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
