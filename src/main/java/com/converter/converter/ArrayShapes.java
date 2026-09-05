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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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
        JsonNode merged = null;
        boolean sawNull = false;
        for (JsonNode item : array) {
            if (item.isNull()) { sawNull = true; continue; }
            merged = merged == null ? item : merge(merged, item);
        }
        if (sawNull) withNullElement.add(array);
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

    private JsonNode merge(JsonNode a, JsonNode b) {
        if (a.isObject() && b.isObject()) return mergeObjects((ObjectNode) a, (ObjectNode) b);
        if (a.isArray() && b.isArray())   return mergeArrays(a, b);
        if (a.isNumber() && b.isNumber()) return wider(a, b);
        if (a.isTextual() && b.isTextual()) {
            // Two dates of the same kind stay that kind; anything else is text,
            // represented by a value no date detector will match.
            return Objects.equals(SourceConventions.temporalTypeFor(a.asText()),
                  SourceConventions.temporalTypeFor(b.asText())) ? a : nodes.textNode("");
        }
        if (a.isBoolean() && b.isBoolean()) return a;
        // Different kinds altogether: only the language's top type fits. A
        // missing node is what the generators already map there, and merging
        // anything further into it leaves it missing.
        return MissingNode.getInstance();
    }

    private ObjectNode mergeObjects(ObjectNode a, ObjectNode b) {
        ObjectNode out = nodes.objectNode();
        Set<String> optional = new LinkedHashSet<>();
        Set<String> inherited = optionalKeys.get(a);
        if (inherited != null) optional.addAll(inherited);
        inherited = optionalKeys.get(b);
        if (inherited != null) optional.addAll(inherited);

        for (Map.Entry<String, JsonNode> entry : a.properties()) {
            String key = entry.getKey();
            JsonNode other = b.get(key);
            if (other == null) {
                optional.add(key);
                out.set(key, entry.getValue());
            } else {
                out.set(key, mergeValues(entry.getValue(), other, key, optional));
            }
        }
        for (Map.Entry<String, JsonNode> entry : b.properties()) {
            if (a.has(entry.getKey())) continue;
            optional.add(entry.getKey());
            out.set(entry.getKey(), entry.getValue());
        }
        if (!optional.isEmpty()) optionalKeys.put(out, optional);
        return out;
    }

    /** A null on either side makes the key optional and lets the other side supply the type. */
    private JsonNode mergeValues(JsonNode a, JsonNode b, String key, Set<String> optional) {
        if (a.isNull() && b.isNull()) return a;
        if (a.isNull()) { optional.add(key); return b; }
        if (b.isNull()) { optional.add(key); return a; }
        return merge(a, b);
    }

    private JsonNode mergeArrays(JsonNode a, JsonNode b) {
        JsonNode elementA = elementOf(a);
        JsonNode elementB = elementOf(b);
        JsonNode element = elementA == null ? elementB
              : elementB == null ? elementA : merge(elementA, elementB);
        ArrayNode out = nodes.arrayNode();
        // Recorded directly rather than re-derived from the contents, so the
        // merged element keeps its identity and its optional keys.
        elements.put(out, element);
        if (hasNullElement(a) || hasNullElement(b)) withNullElement.add(out);
        return out;
    }

    /** The wider of two numbers, by kind: int < long < BigInteger < float < double < BigDecimal. */
    private static JsonNode wider(JsonNode a, JsonNode b) {
        return rank(b) > rank(a) ? b : a;
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
