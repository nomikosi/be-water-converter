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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;

import java.util.ArrayDeque;
import java.util.Deque;
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
        // "\n" rather than the system separator: see PivotJson.prettyPrinter.
        xmlMapper.setDefaultPrettyPrinter(
              new com.fasterxml.jackson.dataformat.xml.util.DefaultXmlPrettyPrinter().withCustomNewLine("\n"));
        // XML has no null, and an empty element reads back as "": the JSON
        // {"middleName": null} came back as {"middleName": ""}. xsi:nil is the
        // standard spelling, and the reader already turns it back into null.
        xmlMapper.enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL);
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

        rejectCharactersXmlCannotHold(node);

        // JSON keys are arbitrary; XML element names are not. Sanitize keys so
        // the output is always well-formed XML ({"first name":1} would
        // otherwise emit the unparseable <first name>1</first name>).
        node = sanitizeKeysForXml(node);

        return xmlMapper.writer().withRootName("root").writeValueAsString(node);
    }

    /**
     * Refuses a value holding a character XML 1.0 cannot: a control character
     * other than tab and the line breaks, U+FFFE, U+FFFF, or half a surrogate
     * pair. The writer wrote U+FFFE as &amp;#xfffe;, which every parser
     * refuses, this plugin's included, and half a pair as it was; it refused
     * a control character itself, as "Invalid white space character (0x1b) in
     * text to output".
     */
    private static void rejectCharactersXmlCannotHold(JsonNode root) {
        java.util.ArrayDeque<JsonNode> nodes = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<String> keys = new java.util.ArrayDeque<>();
        nodes.push(root);
        keys.push("root");
        while (!nodes.isEmpty()) {
            JsonNode node = nodes.pop();
            String key = keys.pop();
            if (node.isTextual()) {
                int character = firstCharacterXmlCannotHold(node.textValue());
                if (character >= 0)
                    throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                          "XML cannot hold the character U+%04X, found in the value of \"%s\". "
                          + "Remove it, or convert to another format.", character, key));
            } else if (node.isObject()) {
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    nodes.push(property.getValue());
                    keys.push(property.getKey());
                }
            } else if (node.isArray()) {
                for (JsonNode item : node) {
                    nodes.push(item);
                    keys.push(key);
                }
            }
        }
    }

    /** The first code point outside XML 1.0's Char production, or -1. Half a surrogate pair counts as one. */
    static int firstCharacterXmlCannotHold(String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            boolean character = c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                  || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF);
            if (!character) return c;
            i += Character.charCount(c);
        }
        return -1;
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
        // The text, not its UTF-8 bytes: the document has been decoded already,
        // and bytes made the parser decode it again by the XML declaration. An
        // encoding="ISO-8859-1" file turned José into JosÃ©, and one declared
        // UTF-16 failed outright.
        JsonNode node = xmlMapper.readTree(xml);
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
        // StAX, not DOM: this runs before every conversion, and building a whole
        // second tree meant a large document was fully parsed and held in memory
        // twice before any converting started. A streaming pass answers the same
        // question and keeps only the names at the current depth.
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        javax.xml.stream.XMLStreamReader reader;
        try {
            reader = factory.createXMLStreamReader(new java.io.StringReader(xml));
        } catch (Exception notParseableHere) {
            return;   // let XmlMapper produce the real parse error
        }
        // One frame per open element: the names its children and attributes have
        // claimed so far, since those all land in the same JSON object.
        Deque<Map<String, Name>> stack = new ArrayDeque<>();
        try {
            while (reader.hasNext()) {
                if (reader.next() != javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    if (reader.getEventType() == javax.xml.stream.XMLStreamConstants.END_ELEMENT
                          && !stack.isEmpty()) stack.pop();
                    continue;
                }
                if (!stack.isEmpty())
                    claim(stack.peek(), reader.getLocalName(), new Name(
                          reader.getNamespaceURI(), reader.getPrefix(), reader.getLocalName(), false));

                Map<String, Name> frame = new java.util.HashMap<>();
                for (int i = 0; i < reader.getAttributeCount(); i++)
                    claim(frame, reader.getAttributeLocalName(i), new Name(
                          reader.getAttributeNamespace(i), reader.getAttributePrefix(i),
                          reader.getAttributeLocalName(i), true));
                stack.push(frame);
            }
        } catch (javax.xml.stream.XMLStreamException notParseableHere) {
            // Malformed: XmlMapper will report it properly.
        } finally {
            try { reader.close(); } catch (javax.xml.stream.XMLStreamException ignored) { }
        }
    }

    /**
     * An XML name as XML sees it: the namespace URI and local part decide
     * identity, the prefix is only how the document spelled it. Comparing
     * prefixes refused {@code <a:v>} beside {@code <b:v>} when both prefixes
     * were bound to the same namespace — one repeated element, which Jackson
     * lists correctly — and would have merged the same prefix bound to two
     * namespaces in different scopes.
     */
    private record Name(String namespace, String prefix, String local, boolean attribute) {
        boolean sameAs(Name other) {
            return attribute == other.attribute && local.equals(other.local)
                  && java.util.Objects.equals(blankToNull(namespace), blankToNull(other.namespace));
        }

        private static String blankToNull(String s) {
            return s == null || s.isEmpty() ? null : s;
        }

        String describe() {
            String spelled = prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
            return attribute ? "the attribute " + spelled : "the element <" + spelled + ">";
        }
    }

    /**
     * Records one name in a frame, refusing when a different XML name has
     * already claimed the same JSON key. The SAME child element appearing twice
     * is an ordinary list rather than a collision; attributes are unique per
     * element, and an attribute "a" beside a child <a> — the same spelling,
     * different things — does collide.
     */
    private static void claim(Map<String, Name> frame, String local, Name name) {
        Name previous = frame.put(local, name);
        if (previous == null || (!name.attribute() && previous.sameAs(name))) return;
        throw new IllegalArgumentException(
              "This XML holds both " + previous.describe() + " and " + name.describe()
              + ", which are different in XML but the same key \"" + local + "\" in JSON, so "
              + "converting would merge them. Rename one, or convert the sections separately.");
    }

    private JsonNode sanitizeKeysForXml(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = jsonMapper.createObjectNode();
            // Distinct keys can sanitize to the same element name ("a b" and
            // "a+b" both become "a_b"); without a counter the second silently
            // overwrites the first and that value is lost. Keys that are already
            // element names keep them, and only the changed ones take a counter:
            // in document order "first name" took first_name, and the key really
            // spelled first_name became first_name_2, so <first_name> held the
            // other key's value.
            Set<String> used = new HashSet<>();
            for (String key : (Iterable<String>) node::fieldNames)
                if (xmlElementName(key).equals(key)) used.add(key);
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String name = xmlElementName(e.getKey());
                out.set(name.equals(e.getKey()) ? name : uniqueElementName(name, used),
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

    /**
     * Maps an arbitrary JSON key to a well-formed XML element name.
     *
     * <p>By the name rules XML readers apply rather than Java's idea of a
     * letter: µ, ª and º are letters to {@link Character#isLetter} and not name
     * characters to XML, so {@code {"latency_µs": 12}} wrote an element this
     * plugin's own reader then refused. The colon is excluded too, because in a
     * name it declares a namespace prefix nobody bound.
     *
     * <p>The readers are Woodstox, behind conversion, and the JDK's parser,
     * behind XML Format. Both take names by XML 1.0's older character tables,
     * and accept exactly the same characters. XML 1.0's fifth edition names
     * more, Ethiopic, Cherokee and Sinhala letters and letters beyond the Basic
     * Multilingual Plane among them, but names written by those rules made a
     * document that neither reader would take, from the plugin's own output.
     * Such characters become underscores, as spaces do.
     */
    static String xmlElementName(String key) {
        if (key == null || key.isEmpty()) return "_";
        StringBuilder sb = new StringBuilder(key.length());
        key.codePoints().forEach(c -> {
            if (c != ':' && isXmlNameChar(c)) sb.appendCodePoint(c);
            else sb.append('_');
        });
        if (!isXmlNameStartChar(sb.codePointAt(0))) sb.insert(0, '_');
        return sb.toString();
    }

    /**
     * A character an element name may start with, by the readers' rules. Past
     * ASCII they are Woodstox's own tables, which the JDK's parser matches
     * character for character; neither takes a character beyond the BMP.
     */
    static boolean isXmlNameStartChar(int c) {
        if (c < 0x80) return c == ':' || c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        return c <= 0xFFFF && com.ctc.wstx.util.XmlChars.is10NameStartChar((char) c);
    }

    /** A character an element name may hold after its first, by the readers' rules. */
    static boolean isXmlNameChar(int c) {
        if (c < 0x80) return isXmlNameStartChar(c) || c == '-' || c == '.' || (c >= '0' && c <= '9');
        return c <= 0xFFFF && com.ctc.wstx.util.XmlChars.is10NameChar((char) c);
    }

    /** Suffixes a counter when a sanitized element name is already used by a sibling. */
    static String uniqueElementName(String name, Set<String> used) {
        if (used.add(name)) return name;
        int n = 2;
        while (!used.add(name + "_" + n)) n++;
        return name + "_" + n;
    }
}
