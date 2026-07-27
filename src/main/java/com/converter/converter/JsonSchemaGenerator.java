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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Infers a JSON Schema (draft 2020-12) from an example document.
 *
 * <p>Every key present in the example is treated as required — an example can
 * only show what <em>is</em> there, never what is optional. Arrays are described
 * by merging their elements, so a heterogeneous array yields an {@code anyOf}
 * rather than silently taking the first element's shape.
 */
public class JsonSchemaGenerator {

    public static final String SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    private final ObjectMapper jsonMapper =
          new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public String fromJson(String json) throws Exception {
        return fromJson(json, true);
    }

    /**
     * @param requireAllKeys when true every observed property is listed in
     *                       {@code required}; when false the schema constrains
     *                       types only.
     */
    public String fromJson(String json, boolean requireAllKeys) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        JsonNode root = jsonMapper.readTree(json);

        ObjectNode schema = describe(root, requireAllKeys);
        // The dialect belongs at the document root only, not on nested subschemas.
        ObjectNode out = jsonMapper.createObjectNode();
        out.put("$schema", SCHEMA_DIALECT);
        out.setAll(schema);
        return jsonMapper.writeValueAsString(out);
    }

    private ObjectNode describe(JsonNode node, boolean requireAllKeys) {
        ObjectNode schema = jsonMapper.createObjectNode();

        if (node.isObject()) {
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = jsonMapper.createArrayNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                properties.set(e.getKey(), describe(e.getValue(), requireAllKeys));
                required.add(e.getKey());
            }
            if (requireAllKeys && !required.isEmpty()) schema.set("required", required);
            return schema;
        }

        if (node.isArray()) {
            schema.put("type", "array");
            if (node.isEmpty()) return schema;   // no elements: no item constraint to infer
            schema.set("items", describeItems(node, requireAllKeys));
            return schema;
        }

        String type = scalarType(node);
        schema.put("type", type);
        return schema;
    }

    /**
     * Describes an array's element type. Elements are merged rather than sampled:
     * uniform arrays collapse to a single subschema, mixed arrays become anyOf.
     */
    private JsonNode describeItems(JsonNode array, boolean requireAllKeys) {
        // LinkedHashSet over the serialized subschemas both de-duplicates and
        // preserves first-seen order, so output is stable for a given input.
        Set<String> seen = new LinkedHashSet<>();
        ArrayNode variants = jsonMapper.createArrayNode();
        for (JsonNode item : array) {
            ObjectNode itemSchema = describe(item, requireAllKeys);
            if (seen.add(itemSchema.toString())) variants.add(itemSchema);
        }
        if (variants.size() == 1) return variants.get(0);
        ObjectNode anyOf = jsonMapper.createObjectNode();
        anyOf.set("anyOf", variants);
        return anyOf;
    }

    private String scalarType(JsonNode node) {
        if (node.isBoolean())      return "boolean";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber())       return "number";
        if (node.isNull())         return "null";
        return "string";
    }
}
