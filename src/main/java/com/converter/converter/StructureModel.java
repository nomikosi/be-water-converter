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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The set of types a JSON document implies, and the name assigned to each.
 *
 * <p>Which objects need their own type is a property of the document, not of the
 * target language, so every code generator shares this. Keeping it in one place
 * also keeps the collision handling in one place: naming was keyed on the type
 * name once, which silently gave two differently-shaped objects the same class.
 */
public final class StructureModel {

    /** Assigned name in discovery order — the first entry is the root. */
    private final LinkedHashMap<String, JsonNode> types = new LinkedHashMap<>();

    /**
     * Identity-keyed on purpose: two objects that want the same name must each
     * get their own type, so equality must be "the same node", not "the same
     * shape" and certainly not "the same name".
     */
    private final Map<JsonNode, String> names = new IdentityHashMap<>();

    private final UnaryOperator<String> typeNamer;

    private StructureModel(UnaryOperator<String> typeNamer) {
        this.typeNamer = typeNamer;
    }

    /**
     * @param typeNamer maps a JSON key to a type name in the target language's
     *                  conventions; supplied by the generator because keyword
     *                  and identifier rules differ per language.
     */
    public static StructureModel from(JsonNode root, String rootName,
          UnaryOperator<String> typeNamer) {
        StructureModel model = new StructureModel(typeNamer);
        model.collect(root, rootName);
        return model;
    }

    private void collect(JsonNode node, String desiredName) {
        if (!node.isObject() || names.containsKey(node)) return;
        String name = unique(desiredName);
        names.put(node, name);
        types.put(name, node);
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String childName = typeNamer.apply(entry.getKey());
            JsonNode child = entry.getValue();
            if (child.isObject()) {
                collect(child, childName);
            } else if (child.isArray() && !child.isEmpty() && child.get(0).isObject()) {
                // Arrays are typed from their first element; see the generators'
                // tests, which pin that this is deliberate.
                collect(child.get(0), childName);
            }
        }
    }

    /** Suffixes a counter when the desired name is already taken. */
    private String unique(String desired) {
        String base = (desired == null || desired.isEmpty()) ? "Type" : desired;
        if (!types.containsKey(base)) return base;
        int n = 2;
        while (types.containsKey(base + n)) n++;
        return base + n;
    }

    /** Every discovered type, in discovery order; the first is the root. */
    public Map<String, JsonNode> types() {
        return Collections.unmodifiableMap(types);
    }

    /** The name assigned to this exact node, or null if it is not a discovered type. */
    public String nameOf(JsonNode node) {
        return names.get(node);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(types.keySet());
    }
}
