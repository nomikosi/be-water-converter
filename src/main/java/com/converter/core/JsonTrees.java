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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Shared, non-mutating ordering for JSON pivots and formatters. */
final class JsonTrees {
    private JsonTrees() {}

    static JsonNode sorted(JsonNode node) { return sorted(node, false); }

    /** Numeric normalization is for comparison only; formatting retains decimal scale. */
    static JsonNode sorted(JsonNode node, boolean normalizeNumbers) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) out.set(name, sorted(node.get(name), normalizeNumbers));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) out.add(sorted(item, normalizeNumbers));
            return out;
        }
        if (normalizeNumbers && node.isNumber()) return canonicalNumber(node.decimalValue());
        return node;
    }

    /**
     * A number in the one form every spelling of its value shares, as Compare
     * shows it: 1, 1.0 and 1e0 all become 1, and 2.50 becomes 2.5.
     *
     * <p>Whole numbers are written out: stripping trailing zeros alone left 30
     * as 3E+1 and 12000 as 1.2E+4 in the diff, a notation nobody wrote. Only
     * within reason — written out, 1e400 would be four hundred digits.
     */
    static JsonNode canonicalNumber(BigDecimal value) {
        BigDecimal canonical = value.stripTrailingZeros();
        if (canonical.scale() < 0 && canonical.precision() - canonical.scale() <= MAX_PLAIN_DIGITS)
            canonical = canonical.setScale(0);
        return DecimalNode.valueOf(canonical);
    }

    /** Integer digits up to which Compare writes a whole number out in full. */
    private static final int MAX_PLAIN_DIGITS = 64;
}
