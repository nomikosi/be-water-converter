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

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 *
 * <p>Arrays are typed from the merged shape of every element, see
 * {@link ArrayShapes}; the generators reach that shape through
 * {@link #elementOf} so the object they type a field from is the one that was
 * named here.
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

    private final ArrayShapes shapes = new ArrayShapes();

    private final UnaryOperator<String> typeNamer;

    /** Names a generated type may not take; see {@link #from}. */
    private final Set<String> reservedNames;

    private StructureModel(UnaryOperator<String> typeNamer, Set<String> reservedNames) {
        this.typeNamer = typeNamer;
        this.reservedNames = reservedNames;
    }

    /**
     * @param root          the document. A root array is unwrapped to the merged
     *                      shape of its elements, all the way down, so an array
     *                      of arrays of objects still yields a root type.
     * @param typeNamer     maps a JSON key to a type name in the target
     *                      language's conventions; supplied by the generator
     *                      because keyword and identifier rules differ per
     *                      language.
     * @param reservedNames type names the generator itself emits — the simple
     *                      names of its imports plus the built-ins it uses as
     *                      field types. A generated class may not take one:
     *                      declaring {@code List} alongside an emitted
     *                      {@code import java.util.List} does not compile, and
     *                      declaring {@code String} silently shadows
     *                      {@code java.lang.String} for the whole file.
     */
    public static StructureModel from(JsonNode root, String rootName,
          UnaryOperator<String> typeNamer, Set<String> reservedNames) {
        StructureModel model = new StructureModel(typeNamer, reservedNames);
        JsonNode rootObject = root;
        while (rootObject != null && rootObject.isArray()) {
            JsonNode element = model.shapes.elementOf(rootObject);
            if (element == null)
                throw new IllegalArgumentException("JSON array is empty — nothing to generate.");
            rootObject = element;
        }
        // A scalar implies no type at all, so generation would otherwise return
        // an empty string and the panel would report that as a conversion.
        if (rootObject == null || !rootObject.isObject())
            throw new IllegalArgumentException(
                  "Nothing to generate: the input must be a JSON object, or an array of objects.");
        model.collect(rootObject, rootName);
        return model;
    }

    private void collect(JsonNode node, String desiredName) {
        if (!node.isObject() || names.containsKey(node)) return;
        // An empty object below the root is no type: the example says nothing
        // about its fields, and the generators give it a map, which reads
        // whatever the real data holds. As a class with no fields it could
        // not be written back by Jackson, and in Kotlin compared by identity.
        if (node.isEmpty() && !types.isEmpty()) return;
        String name = unique(desiredName);
        takenIgnoringCase.add(name.toLowerCase(Locale.ROOT));
        names.put(node, name);
        types.put(name, node);
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String childName = typeNamer.apply(entry.getKey());
            JsonNode child = entry.getValue();
            if (child.isObject()) {
                collect(child, childName);
            } else if (child.isArray()) {
                // Nesting has to be followed all the way down, because the
                // generators type [[{...}]] as List<List<Row>> and that Row
                // still needs a type.
                JsonNode element = shapes.unwrap(child);
                if (element != null) collect(element, childName);
            }
        }
    }

    /** Suffixes a counter when the desired name is already taken. */
    private String unique(String desired) {
        String base = (desired == null || desired.isEmpty()) ? "Type" : desired;
        // Suffixed rather than numbered: "List2" would imply a "List" exists in
        // the generated file, when what it actually collides with is the import.
        // This is the suffix the identifier rules already use for keywords.
        if (reservedNames.contains(base)) base = base + "Value";
        // Ignoring case: each class compiles to a file named after it, and on
        // Windows and macOS Url.class and URL.class are one file. Keys "url"
        // and "URL" left only one of the two classes in the output folder.
        String name = base;
        for (int n = 2; takenIgnoringCase.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + n;
        return name;
    }

    /** Every class name assigned so far, lower-cased. */
    private final Set<String> takenIgnoringCase = new HashSet<>();

    /** Every discovered type, in discovery order; the first is the root. */
    public Map<String, JsonNode> types() {
        return Collections.unmodifiableMap(types);
    }

    /** The name assigned to this exact node, or null if it is not a discovered type. */
    public String nameOf(JsonNode node) {
        return names.get(node);
    }

    /**
     * The shape an array's elements are typed from — every element merged — or
     * null for an array with nothing to type from. A generator must type a
     * collection through this, not through {@code get(0)}, or the object it
     * types from will not be the one that was named.
     */
    public JsonNode elementOf(JsonNode array) {
        return shapes.elementOf(array);
    }

    /** True when the example showed a null among this array's elements. */
    public boolean hasNullElement(JsonNode array) {
        return shapes.hasNullElement(array);
    }

    /**
     * True when the example positively showed this key to be optional: some
     * element of the array the object was merged from lacked it, or had it as
     * null. A key seen in every element is not known to be optional — an
     * example can only show what was present.
     */
    public boolean isOptional(JsonNode object, String key) {
        return shapes.isOptional(object, key);
    }
}
