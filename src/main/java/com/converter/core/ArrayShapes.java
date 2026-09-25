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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The shape an array's elements share, for typing a collection from an
 * example.
 *
 * <p>Arrays used to be typed from their first element alone, so
 * {@code [{"a":1},{"b":"x"}]} produced a class with only {@code a} and
 * {@code [1,"two"]} became {@code List<Integer>}. Every element is merged
 * instead: objects take the union of their keys, numbers widen to the widest
 * kind seen, and values of genuinely different kinds fall back to the
 * language's top type. Keys some elements lack, and elements that were null,
 * are remembered so a generator that can express optionality (Kotlin) can.
 *
 * <p>Merged nodes are fresh objects, keyed by identity in the same way
 * {@link StructureModel} keys its type names, so a merged element can be given
 * a name like any other object.
 */
final class ArrayShapes {

    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    /** Array to its merged element; null when the array has no non-null element. */
    private final Map<JsonNode, JsonNode> elements = new IdentityHashMap<>();

    /** Merged object to the keys absent, or null, in at least one contributing element. */
    private final Map<JsonNode, Set<String>> optionalKeys = new IdentityHashMap<>();

    /** Arrays that held a null element somewhere. */
    private final Set<JsonNode> withNullElement =
          Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The merged element of an array, or null when it has none to type from.
     * A single-element array yields that element itself, so an object that
     * appears once keeps its identity.
     */
    JsonNode elementOf(JsonNode array) {
        if (elements.containsKey(array)) return elements.get(array);
        Accumulator shape = new Accumulator();
        for (JsonNode item : array) shape.add(item);
        JsonNode merged = shape.element();
        if (shape.sawNull) withNullElement.add(array);
        elements.put(array, merged);
        return merged;
    }

    /** The node an array bottoms out at, unwrapping arrays of arrays; the node itself when not an array. */
    JsonNode unwrap(JsonNode node) {
        while (node != null && node.isArray()) node = elementOf(node);
        return node;
    }

    /** Nested array levels: 0 for a non-array, 1 for {@code [1]}, 2 for {@code [[1]]}. */
    int depth(JsonNode node) {
        int depth = 0;
        while (node != null && node.isArray()) {
            depth++;
            node = elementOf(node);
        }
        return depth;
    }

    /** True when the example showed a null among this array's elements. */
    boolean hasNullElement(JsonNode array) {
        elementOf(array);
        return withNullElement.contains(array);
    }

    /** True when some element contributing to this merged object lacked the key, or had it as null. */
    boolean isOptional(JsonNode object, String key) {
        Set<String> keys = optionalKeys.get(object);
        return keys != null && keys.contains(key);
    }

    /**
     * Accumulates each observed value once. Presence counts establish optional
     * keys without copying an ever-growing union or revisiting absent fields.
     * Only finish() publishes nodes and metadata; intermediate shapes are not
     * retained by the identity caches.
     */
    private final class Accumulator {
        private JsonNode representative;
        private Map<String, Accumulator> fields;
        private Accumulator items;
        private int samples;
        private int nonNullSamples;
        private boolean sawNull;

        void add(JsonNode value) {
            samples++;
            if (value.isNull()) { sawNull = true; return; }
            nonNullSamples++;
            if (representative == null) {
                representative = value;
                if (value.isObject()) fields = new LinkedHashMap<>();
                if (value.isArray()) items = new Accumulator();
            }
            if (representative.isObject() && value.isObject()) {
                for (Map.Entry<String, JsonNode> entry : value.properties())
                    fields.computeIfAbsent(entry.getKey(), ignored -> new Accumulator()).add(entry.getValue());
            } else if (representative.isArray() && value.isArray()) {
                for (JsonNode item : value) items.add(item);
            } else if (representative.isNumber() && value.isNumber()) {
                representative = wider(representative, value);
            } else if (representative.isTextual() && value.isTextual()) {
                if (!Objects.equals(SourceConventions.temporalTypeFor(representative.asText()),
                      SourceConventions.temporalTypeFor(value.asText()))) representative = nodes.textNode("");
            } else if (!(representative.isBoolean() && value.isBoolean())) {
                representative = MissingNode.getInstance();
                fields = null;
                items = null;
            }
        }

        JsonNode element() { return nonNullSamples == 0 ? null : finish(); }

        JsonNode finish() {
            if (nonNullSamples == 0) return nodes.nullNode();
            // A lone object/array keeps its original identity. No source node
            // is mutated, including when one input occurs in multiple scopes.
            if (nonNullSamples == 1) return representative;
            if (fields != null) {
                ObjectNode out = nodes.objectNode();
                Set<String> optional = new LinkedHashSet<>();
                for (Map.Entry<String, Accumulator> entry : fields.entrySet()) {
                    Accumulator child = entry.getValue();
                    out.set(entry.getKey(), child.finish());
                    if (child.samples < nonNullSamples || child.sawNull) optional.add(entry.getKey());
                }
                if (!optional.isEmpty()) optionalKeys.put(out, optional);
                return out;
            }
            if (items != null) {
                ArrayNode out = nodes.arrayNode();
                elements.put(out, items.element());
                if (items.sawNull) withNullElement.add(out);
                return out;
            }
            return representative;
        }
    }

    /** The wider of two numbers, by kind: int < long < BigInteger < float < double < BigDecimal. */
    private static JsonNode wider(JsonNode a, JsonNode b) {
        JsonNode precise = !GeneratorJson.canUseDouble(a) ? a
              : !GeneratorJson.canUseDouble(b) ? b : null;
        if (precise != null && (a.isFloatingPointNumber() || b.isFloatingPointNumber()))
            return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(precise.decimalValue());

        JsonNode widest = rank(b) > rank(a) ? b : a;
        // Keep both the widest integral kind and evidence that a sample needs
        // exact precision. A later decimal must see that evidence regardless
        // of the order in which long and BigInteger samples were merged.
        if (precise != null && widest.isIntegralNumber())
            return widest.isBigInteger()
                  ? com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(precise.bigIntegerValue())
                  : precise;
        return widest;
    }

    private static int rank(JsonNode number) {
        if (number.isBigDecimal()) return 6;
        if (number.isDouble())     return 5;
        if (number.isFloat())      return 4;
        if (number.isBigInteger()) return 3;
        if (number.isLong())       return 2;
        return 1;
    }
}
