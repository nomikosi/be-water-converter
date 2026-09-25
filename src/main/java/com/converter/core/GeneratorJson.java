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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** Reads exact values before choosing the numeric shapes used by code generators. */
final class GeneratorJson {
    private static final ObjectMapper JSON = PivotJson.mapper();

    private GeneratorJson() {}

    static JsonNode readTree(String json) throws Exception {
        return classify(JSON.readTree(json));
    }

    private static JsonNode classify(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> entry.setValue(classify(entry.getValue())));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) array.set(i, classify(array.get(i)));
        } else if (node != null && node.isBigDecimal() && canUseDouble(node)) {
            return DoubleNode.valueOf(node.doubleValue());
        }
        return node;
    }

    // Preserve familiar Double types for ordinary JSON decimals, but require
    // their shortest decimal representation to retain the exact input value.
    static boolean canUseDouble(JsonNode number) {
        double value = number.doubleValue();
        return Double.isFinite(value)
              && BigDecimal.valueOf(value).compareTo(number.decimalValue()) == 0;
    }
}
