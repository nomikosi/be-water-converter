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
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class JsonXmlConverter {
    private final ObjectMapper jsonMapper;
    private final XmlMapper xmlMapper;

    public JsonXmlConverter() {
        jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        xmlMapper  = new XmlMapper();
        xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String jsonToXml(String json) throws Exception {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Input JSON must not be empty");
        }
        JsonNode node = jsonMapper.readTree(json);

        // XML requires a single root — wrap bare arrays automatically
        if (node.isArray()) {
            node = jsonMapper.createObjectNode().set("items", node);
        }

        // JSON keys are arbitrary; XML element names are not. Sanitize keys so
        // the output is always well-formed XML ({"first name":1} would
        // otherwise emit the unparseable <first name>1</first name>).
        node = sanitizeKeysForXml(node);

        return xmlMapper.writer().withRootName("root").writeValueAsString(node);
    }

    public String xmlToJson(String xml) throws Exception {
        return xmlToJson(xml, false);
    }

    /**
     * @param inferTypes when true, textual leaf values that look like numbers,
     *                   booleans or null become typed JSON values (XML carries
     *                   no type information, so everything is a string by default).
     */
    public String xmlToJson(String xml, boolean inferTypes) throws Exception {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("Input XML must not be empty");
        }
        JsonNode node = xmlMapper.readTree(xml.getBytes(StandardCharsets.UTF_8));
        if (inferTypes) node = inferLeafTypes(node);
        return jsonMapper.writeValueAsString(node);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JsonNode inferLeafTypes(JsonNode node) {
        if (node.isTextual()) {
            return ScalarInference.infer(node.asText(), jsonMapper.getNodeFactory());
        }
        if (node.isObject()) {
            ObjectNode out = jsonMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.set(e.getKey(), inferLeafTypes(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = jsonMapper.createArrayNode();
            for (JsonNode item : node) out.add(inferLeafTypes(item));
            return out;
        }
        return node;
    }

    private JsonNode sanitizeKeysForXml(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = jsonMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.set(xmlElementName(e.getKey()), sanitizeKeysForXml(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = jsonMapper.createArrayNode();
            for (JsonNode item : node) out.add(sanitizeKeysForXml(item));
            return out;
        }
        return node;
    }

    /** Maps an arbitrary JSON key to a well-formed XML element name. */
    static String xmlElementName(String key) {
        if (key == null || key.isEmpty()) return "_";
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean valid = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
            sb.append(valid ? c : '_');
        }
        char first = sb.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
