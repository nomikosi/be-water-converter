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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JsonXmlConverter {
    private final ObjectMapper jsonMapper;
    private final XmlMapper xmlMapper;

    public JsonXmlConverter() {
        // No INDENT_OUTPUT: this mapper only ever writes the internal JSON
        // pivot, which the next stage re-parses and nobody reads. Indenting it
        // measured 1.29-1.45x the compact size for no benefit.
        jsonMapper = PivotJson.mapper();
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
        rejectMergingNames(xml);
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

    /**
     * Refuses XML whose element and attribute names collide once Jackson drops
     * namespace prefixes.
     *
     * <p>Jackson's tree API keys on the LOCAL name, so {@code <p:a>1</p:a>} and
     * {@code <q:a>2</q:a>} — different elements in different namespaces —
     * became the single key {@code a} holding {@code ["1","2"]}, and an
     * attribute {@code a="1"} merged with a child {@code <a>} the same way. The
     * values survive but the distinction does not, and nothing said so.
     *
     * <p>Only an actual collision is refused: ordinary namespaced XML, where
     * local names stay distinct, converts exactly as before.
     */
    private static void rejectMergingNames(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf =
              javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setExpandEntityReferences(false);
        org.w3c.dom.Document doc;
        try {
            doc = dbf.newDocumentBuilder().parse(
                  new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (Exception notParseableHere) {
            return;   // let XmlMapper produce the real parse error
        }
        checkElement(doc.getDocumentElement());
    }

    private static void checkElement(org.w3c.dom.Element element) {
        // Attributes and child elements land in the same JSON object, so an
        // attribute named "a" and a child <a> collide even though both spell "a".
        Map<String, String> attributeNames = new java.util.HashMap<>();
        org.w3c.dom.NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node a = attrs.item(i);
            if ("xmlns".equals(a.getPrefix()) || "xmlns".equals(a.getNodeName())) continue;
            String local = a.getLocalName() == null ? a.getNodeName() : a.getLocalName();
            String previous = attributeNames.put(local, a.getNodeName());
            if (previous != null)
                throw collision(element, previous, a.getNodeName(), local);
        }
        Map<String, String> elementNames = new java.util.HashMap<>();
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof org.w3c.dom.Element child)) continue;
            String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            String attribute = attributeNames.get(local);
            if (attribute != null)
                throw collision(element, "the attribute " + attribute,
                      "the element <" + child.getNodeName() + ">", local);
            // Repeating the SAME element name is an ordinary XML list.
            String previous = elementNames.put(local, child.getNodeName());
            if (previous != null && !previous.equals(child.getNodeName()))
                throw collision(element, previous, child.getNodeName(), local);
            checkElement(child);
        }
    }

    private static IllegalArgumentException collision(org.w3c.dom.Element parent,
          String one, String other, String key) {
        return new IllegalArgumentException(
              "<" + parent.getNodeName() + "> holds both " + one + " and " + other
              + ", which are different in XML but the same key \"" + key + "\" in JSON, so "
              + "converting would merge them. Rename one, or convert the sections separately.");
    }

    private JsonNode sanitizeKeysForXml(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = jsonMapper.createObjectNode();
            // Distinct keys can sanitize to the same element name ("a b" and
            // "a+b" both become "a_b"); without a counter the second silently
            // overwrites the first and that value is lost.
            Set<String> used = new HashSet<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.set(uniqueElementName(xmlElementName(e.getKey()), used),
                      sanitizeKeysForXml(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = jsonMapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode child = sanitizeKeysForXml(item);
                // XML has no nested-list form: two levels of array serialised as
                // one flat run of elements, so {"m":[[1,2],[3,4]]} came back as
                // {"m":[1,2,3,4]} with the rows merged and a dimension gone.
                // Wrapping each inner list in an element keeps the grouping, the
                // same trade ProtoConverter makes for repeated-of-repeated.
                out.add(item.isArray()
                      ? jsonMapper.createObjectNode().set(NESTED_ARRAY_ELEMENT, child)
                      : child);
            }
            return out;
        }
        return node;
    }

    /**
     * Element that stands in for one nested array level.
     *
     * <p>Not reserved by construction: a document that genuinely contains
     * {@code {"m":[{"values":[1,2]}]}} produces byte-identical XML, so
     * {@code xmlToJson} cannot unwrap it again without corrupting that case.
     * The round trip therefore gains this level rather than losing a dimension
     * — a deliberate choice of consumable XML over a recoverable round trip.
     */
    static final String NESTED_ARRAY_ELEMENT = "values";

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

    /** Suffixes a counter when a sanitized element name is already used by a sibling. */
    static String uniqueElementName(String name, Set<String> used) {
        if (used.add(name)) return name;
        int n = 2;
        while (!used.add(name + "_" + n)) n++;
        return name + "_" + n;
    }
}
