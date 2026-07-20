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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.regex.Pattern;

/**
 * Shared scalar type inference for formats whose values are inherently
 * untyped strings (CSV cells, XML text). Values that look like integers,
 * decimals, booleans or {@code null} become typed JSON nodes; values with
 * leading zeros ("007") and integers beyond 64 bits stay strings so
 * identifiers are never mangled.
 */
final class ScalarInference {

    /** Integer without leading zeros ("007" stays a string). */
    private static final Pattern INT_PATTERN = Pattern.compile("-?(0|[1-9]\\d*)");
    /** Decimal / scientific notation with a fraction or exponent part. */
    private static final Pattern DEC_PATTERN =
          Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+([eE][+-]?\\d+)?|[eE][+-]?\\d+)");

    private ScalarInference() {}

    static JsonNode infer(String value, JsonNodeFactory nf) {
        if (value == null) return nf.nullNode();
        if (value.isEmpty()) return nf.textNode("");
        switch (value) {
            case "true"  -> { return nf.booleanNode(true); }
            case "false" -> { return nf.booleanNode(false); }
            case "null"  -> { return nf.nullNode(); }
        }
        if (INT_PATTERN.matcher(value).matches()) {
            try {
                long v = Long.parseLong(value);
                return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                      ? nf.numberNode((int) v) : nf.numberNode(v);
            } catch (NumberFormatException overflow) {
                return nf.textNode(value);
            }
        }
        if (DEC_PATTERN.matcher(value).matches()) {
            double d = Double.parseDouble(value);
            if (!Double.isInfinite(d)) return nf.numberNode(d);
        }
        return nf.textNode(value);
    }
}
