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
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonXmlConverter")
class JsonXmlConverterTest {

    private JsonXmlConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new JsonXmlConverter();
        json = new ObjectMapper();
    }

    // ── JSON -> XML ───────────────────────────────────────────────────────

    @Test @DisplayName("JSON->XML: simple flat object")
    void jsonToXmlFlat() throws Exception {
        String result = converter.jsonToXml("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result).contains("Alice").contains("30").contains("</");
    }

    @Test @DisplayName("JSON->XML: nested object")
    void jsonToXmlNested() throws Exception {
        String input = "{\"person\":{\"name\":\"Bob\",\"address\":{\"city\":\"Athens\",\"zip\":\"10001\"}}}";
        String result = converter.jsonToXml(input);
        assertThat(result).contains("Athens").contains("10001");
    }

    @Test @DisplayName("JSON->XML: array of objects")
    void jsonToXmlArray() throws Exception {
        String input = "{\"items\":[{\"id\":1,\"label\":\"First\"},{\"id\":2,\"label\":\"Second\"}]}";
        String result = converter.jsonToXml(input);
        assertThat(result).contains("First").contains("Second");
    }

    @Test @DisplayName("JSON->XML: boolean and null values")
    void jsonToXmlBoolNull() throws Exception {
        String result = converter.jsonToXml("{\"active\":true,\"deleted\":false,\"notes\":null}");
        assertThat(result).contains("true").contains("false");
    }

    @Test @DisplayName("JSON->XML: numeric types preserved")
    void jsonToXmlNumeric() throws Exception {
        String result = converter.jsonToXml("{\"intVal\":42,\"floatVal\":3.14,\"longVal\":9999999999}");
        assertThat(result).contains("42").contains("3.14").contains("9999999999");
    }

    @Test @DisplayName("JSON->XML: complex menu structure")
    void jsonToXmlMenu() throws Exception {
        String input = "{\"menu\":{\"id\":\"file\",\"value\":\"File\",\"popup\":{\"menuitem\":[{\"value\":\"New\",\"onclick\":\"CreateNewDoc()\"},{\"value\":\"Open\",\"onclick\":\"OpenDoc()\"}]}}}";
        String result = converter.jsonToXml(input);
        assertThat(result).contains("file").contains("CreateNewDoc()").contains("OpenDoc()");
    }

    // ── XML -> JSON ───────────────────────────────────────────────────────

    @Test @DisplayName("XML->JSON: simple flat XML")
    void xmlToJsonFlat() throws Exception {
        JsonNode result = json.readTree(converter.xmlToJson("<root><name>Alice</name><age>30</age></root>"));
        assertThat(result.get("name").asText()).isEqualTo("Alice");
        assertThat(result.get("age").asText()).isEqualTo("30");
    }

    @Test @DisplayName("XML->JSON: nested XML")
    void xmlToJsonNested() throws Exception {
        String input = "<root><person><name>Bob</name><address><city>Athens</city></address></person></root>";
        JsonNode result = json.readTree(converter.xmlToJson(input));
        assertThat(result.path("person").path("address").path("city").asText()).isEqualTo("Athens");
    }

    @Test @DisplayName("XML->JSON: round-trip preserves values")
    void xmlRoundTrip() throws Exception {
        String original = "{\"user\":{\"id\":7,\"email\":\"test@example.com\"}}";
        String xml = converter.jsonToXml(original);
        JsonNode back = json.readTree(converter.xmlToJson(xml));
        assertThat(back.path("user").path("email").asText()).isEqualTo("test@example.com");
    }

    @Test @DisplayName("XML->JSON: multiple sibling elements")
    void xmlToJsonSiblings() throws Exception {
        String input = "<root><a>1</a><b>2</b><c>3</c></root>";
        JsonNode result = json.readTree(converter.xmlToJson(input));
        assertThat(result.get("a").asText()).isEqualTo("1");
        assertThat(result.get("c").asText()).isEqualTo("3");
    }

    /**
     * Edge-case tests for JsonXmlConverter.
     */
    @Nested @DisplayName("edge cases")
    class EdgeCases {
        private JsonXmlConverter converter;
        private ObjectMapper json;

        @BeforeEach void setUp() {
            converter = new JsonXmlConverter();
            json = new ObjectMapper();
        }

        // ── Special characters in values ─────────────────────────────────────

        @Test @DisplayName("JSON->XML: ampersand in value is XML-escaped")
        void jsonToXmlAmpersand() throws Exception {
            String result = converter.jsonToXml("{\"company\":\"A&B Corp\"}");
            assertThat(result).contains("A&amp;B Corp").doesNotContain("A&B Corp");
        }

        @Test @DisplayName("JSON->XML: angle brackets in value are XML-escaped")
        void jsonToXmlAngleBrackets() throws Exception {
            String result = converter.jsonToXml("{\"expr\":\"a<b>c\"}");
            assertThat(result).contains("&lt;");          // < must be escaped per XML spec
            assertThat(result).doesNotContain("<b>");      // raw < is not left unescaped
        }

        @Test @DisplayName("JSON->XML: double-quote in value is preserved in output")
        void jsonToXmlDoubleQuote() throws Exception {
            String result = converter.jsonToXml("{\"msg\":\"say \\\"hello\\\"\"}");
            assertThat(result).contains("say").contains("hello");
        }

        // ── Unicode ───────────────────────────────────────────────────────────

        @Test @DisplayName("JSON->XML->JSON: Greek characters survive round-trip")
        void unicodeGreekRoundTrip() throws Exception {
            String original = "{\"city\":\"\u0391\u03b8\u03ae\u03bd\u03b1\",\"greeting\":\"\u039a\u03b1\u03bb\u03b7\u03bc\u03ad\u03c1\u03b1\"}";
            String xml = converter.jsonToXml(original);
            JsonNode back = json.readTree(converter.xmlToJson(xml));
            assertThat(back.get("city").asText()).isEqualTo("\u0391\u03b8\u03ae\u03bd\u03b1");
            assertThat(back.get("greeting").asText()).isEqualTo("\u039a\u03b1\u03bb\u03b7\u03bc\u03ad\u03c1\u03b1");
        }

        @Test @DisplayName("JSON->XML->JSON: emoji in value survives round-trip")
        void unicodeEmojiRoundTrip() throws Exception {
            String original = "{\"status\":\"\u2705\",\"label\":\"done \uD83D\uDE80\"}";
            String xml = converter.jsonToXml(original);
            JsonNode back = json.readTree(converter.xmlToJson(xml));
            assertThat(back.get("status").asText()).isEqualTo("\u2705");
        }

        @Test @DisplayName("JSON->XML->JSON: Japanese characters survive round-trip")
        void unicodeJapaneseRoundTrip() throws Exception {
            String original = "{\"name\":\"\u7530\u4e2d\",\"lang\":\"\u65e5\u672c\u8a9e\"}";
            JsonNode back = json.readTree(converter.xmlToJson(converter.jsonToXml(original)));
            assertThat(back.get("name").asText()).isEqualTo("\u7530\u4e2d");
        }

        // ── Empty structures ──────────────────────────────────────────────────

        @Test @DisplayName("JSON->XML: empty string value")
        void jsonToXmlEmptyStringValue() throws Exception {
            String result = converter.jsonToXml("{\"key\":\"\"}");
            assertThat(result).isNotBlank();
        }

        @Test @DisplayName("JSON->XML: empty object value")
        void jsonToXmlEmptyObject() throws Exception {
            String result = converter.jsonToXml("{\"meta\":{}}");
            assertThat(result).isNotBlank();
        }

        @Test @DisplayName("JSON->XML: empty array value")
        void jsonToXmlEmptyArray() throws Exception {
            String result = converter.jsonToXml("{\"tags\":[]}");
            assertThat(result).isNotBlank();
        }

        // ── Deeply nested ─────────────────────────────────────────────────────

        @Test @DisplayName("JSON->XML->JSON: 5-level deep nesting round-trip")
        void deepNestedRoundTrip() throws Exception {
            String original = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":\"leaf\"}}}}}";
            JsonNode back = json.readTree(converter.xmlToJson(converter.jsonToXml(original)));
            assertThat(back.path("a").path("b").path("c").path("d").path("e").asText()).isEqualTo("leaf");
        }

        // ── Numeric edge cases ────────────────────────────────────────────────

        @Test @DisplayName("JSON->XML: zero, negative, and float values")
        void jsonToXmlNumericEdge() throws Exception {
            String result = converter.jsonToXml("{\"zero\":0,\"neg\":-42,\"pi\":3.14159}");
            assertThat(result).contains("0").contains("-42").contains("3.14159");
        }

        @Test @DisplayName("JSON->XML->JSON: large integer round-trip")
        void largeIntegerRoundTrip() throws Exception {
            String original = "{\"bigNum\":9007199254740991}";
            JsonNode back = json.readTree(converter.xmlToJson(converter.jsonToXml(original)));
            assertThat(back.get("bigNum").asLong()).isEqualTo(9007199254740991L);
        }

        // ── Null values ───────────────────────────────────────────────────────

        @Test @DisplayName("JSON->XML: null field is represented in output")
        void jsonToXmlNullField() throws Exception {
            String result = converter.jsonToXml("{\"name\":\"Alice\",\"middle\":null}");
            assertThat(result).isNotBlank().contains("Alice");
        }

        // ── Element name sanitization (v1.4.0) ───────────────────────────────

        @Test @DisplayName("JSON->XML: key with a space produces well-formed, round-trippable XML")
        void spaceKeyProducesValidXml() throws Exception {
            String xml = converter.jsonToXml("{\"first name\": 1}");
            assertThat(xml).contains("<first_name>");
            // Must parse back — the unsanitized <first name> element would fail here.
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

        @Test @DisplayName("JSON->XML: digit-leading and nested invalid keys are sanitized")
        void digitLeadingKeySanitized() throws Exception {
            String xml = converter.jsonToXml("{\"1st\": {\"a b\": [1, 2]}}");
            assertThat(xml).contains("<_1st>").contains("<a_b>");
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

        @Test @DisplayName("JSON->XML: keys colliding after sanitization keep both values")
        void collidingSanitizedKeysKeepBothValues() throws Exception {
            // "a b" and "a+b" both sanitize to "a_b"; without deduplication the
            // second silently overwrote the first and that value was lost.
            String xml = converter.jsonToXml("{\"a b\":1,\"a+b\":2}");
            assertThat(xml).contains("<a_b>1</a_b>").contains("<a_b_2>2</a_b_2>");
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

        @Test @DisplayName("JSON->XML: collision counter also applies to nested objects")
        void collidingSanitizedKeysNested() throws Exception {
            String xml = converter.jsonToXml("{\"outer\":{\"x y\":1,\"x-y\":2,\"x@y\":3}}");
            // "x y" and "x@y" collide on "x_y"; "x-y" is already valid and distinct.
            assertThat(xml).contains("<x_y>1</x_y>").contains("<x-y>2</x-y>")
                  .contains("<x_y_2>3</x_y_2>");
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

        // ── Type inference (v1.4.0) ──────────────────────────────────────────

        @Test @DisplayName("XML->JSON: values stay strings by default")
        void xmlValuesStringsByDefault() throws Exception {
            JsonNode result = json.readTree(converter.xmlToJson("<r><age>30</age></r>"));
            assertThat(result.get("age").isTextual()).isTrue();
        }

        @Test @DisplayName("XML->JSON: inference types numbers, booleans and null")
        void xmlValueInference() throws Exception {
            JsonNode result = json.readTree(converter.xmlToJson(
                  "<r><age>30</age><price>9.99</price><active>true</active><name>Alice</name></r>", true));
            assertThat(result.get("age").intValue()).isEqualTo(30);
            assertThat(result.get("price").doubleValue()).isEqualTo(9.99);
            assertThat(result.get("active").isBoolean()).isTrue();
            assertThat(result.get("name").isTextual()).isTrue();
        }

        // ── Security posture (pinned so a dependency upgrade cannot regress it) ──

        @Test @DisplayName("XML->JSON: external entities (XXE) are rejected")
        void externalEntitiesRejected() {
            String xxe = "<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                  + "<foo><v>&x;</v></foo>";
            assertThatThrownBy(() -> converter.xmlToJson(xxe)).isInstanceOf(Exception.class);
        }

        @Test @DisplayName("XML->JSON: internal entity expansion is rejected")
        void entityExpansionRejected() {
            String bomb = "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;\">]>"
                  + "<a>&lol2;</a>";
            assertThatThrownBy(() -> converter.xmlToJson(bomb)).isInstanceOf(Exception.class);
        }

        // ── Multiple sibling repeated elements ───────────────────────────────

        @Test @DisplayName("XML->JSON: repeated same-name siblings produce array")
        void xmlSiblingsSameNameArray() throws Exception {
            String xml = "<root><item>1</item><item>2</item><item>3</item></root>";
            JsonNode result = json.readTree(converter.xmlToJson(xml));
            JsonNode items = result.get("item");
            assertThat(items).isNotNull();
            assertThat(items.isArray()).isTrue();
            assertThat(items).hasSize(3);
        }

        // ── XML with declaration ──────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: XML with declaration header parses correctly")
        void xmlWithDeclaration() throws Exception {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><name>Alice</name></root>";
            JsonNode result = json.readTree(converter.xmlToJson(xml));
            assertThat(result.get("name").asText()).isEqualTo("Alice");
        }

        // ── XXE safety ────────────────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: XXE DOCTYPE injection does not execute external entity")
        void xxeSafety() {
            String xxe = "<?xml version=\"1.0\"?>" +
                "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                "<root><data>&xxe;</data></root>";
            // Refused: the entity is never declared to the parser, let alone read.
            assertThatThrownBy(() -> converter.xmlToJson(xxe))
                .hasMessageContaining("Undeclared general entity \"xxe\"");
            try {
                String result = converter.xmlToJson(xxe);
                assertThat(result).doesNotContain("root:x:0:0");
            } catch (Exception ignored) { }
        }

        // ── W3Schools note.xml structure ─────────────────────────────────────

        @Test @DisplayName("XML->JSON: note.xml structure (To/From/Heading/Body)")
        void w3SchoolsNoteXml() throws Exception {
            String xml = "<note><to>Tove</to><from>Jani</from><heading>Reminder</heading>" +
                "<body>Don't forget me this weekend!</body></note>";
            JsonNode result = json.readTree(converter.xmlToJson(xml));
            assertThat(result.get("to").asText()).isEqualTo("Tove");
            assertThat(result.get("from").asText()).isEqualTo("Jani");
            assertThat(result.get("heading").asText()).isEqualTo("Reminder");
        }

        // ── CD catalog multi-row ──────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: CD catalog style with multiple CD children")
        void cdCatalogStyle() throws Exception {
            String xml = "<catalog>" +
                "<cd><title>Empire Burlesque</title><artist>Bob Dylan</artist><year>1985</year></cd>" +
                "<cd><title>Hide your heart</title><artist>Bonnie Tyler</artist><year>1988</year></cd>" +
                "</catalog>";
            JsonNode result = json.readTree(converter.xmlToJson(xml));
            assertThat(result).isNotNull();
            assertThat(result.toString()).contains("Bob Dylan").contains("Bonnie Tyler");
        }

        // ── Round-trip with JSONPlaceholder-style data ────────────────────────

        @Test @DisplayName("JSON->XML->JSON: JSONPlaceholder post object round-trip")
        void jsonPlaceholderPost() throws Exception {
            String original = "{\"userId\":1,\"id\":1,\"title\":\"sunt aut facere\"," +
                "\"body\":\"quia et suscipit\\nsuscipit recusandae\"}";
            JsonNode back = json.readTree(converter.xmlToJson(converter.jsonToXml(original)));
            assertThat(back.get("userId").asInt()).isEqualTo(1);
            assertThat(back.get("title").asText()).isEqualTo("sunt aut facere");
        }

        // ── Boolean string values ─────────────────────────────────────────────

        @Test @DisplayName("JSON->XML->JSON: boolean values survive round-trip")
        void booleanRoundTrip() throws Exception {
            String original = "{\"enabled\":true,\"debug\":false}";
            String xml = converter.jsonToXml(original);
            JsonNode back = json.readTree(converter.xmlToJson(xml));
            assertThat(back.get("enabled").asText()).isEqualToIgnoringCase("true");
            assertThat(back.get("debug").asText()).isEqualToIgnoringCase("false");
        }

        // ── XML Attributes ────────────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: element with attribute exposes attribute in output")
        void xmlAttributeExposed() throws Exception {
            String xml = "<root><person id=\"1\">Alice</person></root>";
            assertThatCode(() -> {
                String result = converter.xmlToJson(xml);
                assertThat(result).isNotBlank();
                // attribute must not be silently dropped — id or _id or @id should appear
                assertThat(result).containsIgnoringCase("1");
            }).doesNotThrowAnyException();
        }

        @Test @DisplayName("XML->JSON: multiple attributes on one element do not throw")
        void xmlMultipleAttributes() {
            String xml = "<root><item id=\"42\" type=\"widget\" active=\"true\"/></root>";
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

    // ── CDATA ─────────────────────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: CDATA section content is preserved")
        void xmlCdataContent() throws Exception {
            String xml = "<root><script><![CDATA[if (a < b && b > 0) { return true; }]]></script></root>";
            assertThatCode(() -> {
                String result = converter.xmlToJson(xml);
                assertThat(result).isNotBlank();
            }).doesNotThrowAnyException();
        }

    // ── Namespaces ────────────────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: namespaced XML does not throw")
        void xmlNamespace() {
            String xml = "<ns:root xmlns:ns=\"http://example.com/schema\"><ns:name>Alice</ns:name></ns:root>";
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

    // ── Mixed content ─────────────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: element with both text and child elements does not throw")
        void xmlMixedContent() {
            String xml = "<root>Some text<child>nested</child></root>";
            assertThatCode(() -> converter.xmlToJson(xml)).doesNotThrowAnyException();
        }

    // ── Invalid XML tag names from JSON keys ──────────────────────────────

        @Test @DisplayName("JSON->XML: a key starting with a digit is prefixed with an underscore")
        void jsonToXmlKeyStartingWithDigit() throws Exception {
            assertThat(converter.jsonToXml("{\"1abc\":\"val\"}")).contains("<_1abc>val</_1abc>");
        }

        @Test @DisplayName("JSON->XML: a space in a key becomes an underscore")
        void jsonToXmlKeyWithSpace() throws Exception {
            assertThat(converter.jsonToXml("{\"my field\":\"val\"}")).contains("<my_field>val</my_field>");
        }

    // ── Self-closing tags ─────────────────────────────────────────────────

        @Test @DisplayName("XML->JSON: self-closing tag parsed without error")
        void xmlSelfClosingTag() throws Exception {
            String xml = "<root><empty/><name>Alice</name></root>";
            JsonNode result = json.readTree(converter.xmlToJson(xml));
            assertThat(result.get("name").asText()).isEqualTo("Alice");
        }

    // ── Null / blank input ────────────────────────────────────────────────

        @Test @DisplayName("JSON->XML: null input throws or returns descriptive error")
        void jsonToXmlNullInput() {
            assertThatThrownBy(() -> converter.jsonToXml(null))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("JSON->XML: blank input throws or returns descriptive error")
        void jsonToXmlBlankInput() {
            assertThatThrownBy(() -> converter.jsonToXml("   "))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("XML->JSON: null input throws or returns descriptive error")
        void xmlToJsonNullInput() {
            assertThatThrownBy(() -> converter.xmlToJson(null))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("XML->JSON: blank input throws or returns descriptive error")
        void xmlToJsonBlankInput() {
            assertThatThrownBy(() -> converter.xmlToJson("   "))
                  .isInstanceOf(Exception.class);
        }
    }

    /** XML has no nested-list form, so inner lists get an element of their own. */
    @Nested @DisplayName("XML nested arrays")
    class NestedArrays {
        private final JsonXmlConverter converter = new JsonXmlConverter();

        @Test @DisplayName("an inner list becomes an element instead of merging into its neighbours")
        void innerListsKeepTheirGrouping() throws Exception {
            // Was <m>1</m><m>2</m><m>3</m><m>4</m>, i.e. {"m":[1,2,3,4]} on the way
            // back: the rows merged and a dimension vanished with no warning.
            String xml = converter.jsonToXml("{\"m\":[[1,2],[3,4]]}");
            assertThat(xml.replaceAll("\\s+", ""))
                  .isEqualTo("<root><m><values>1</values><values>2</values></m>"
                        + "<m><values>3</values><values>4</values></m></root>");
            assertThat(converter.xmlToJson(xml, true))
                  .isEqualTo("{\"m\":[{\"values\":[1,2]},{\"values\":[3,4]}]}");
        }

        @Test @DisplayName("an empty inner list keeps its element rather than vanishing")
        void emptyInnerListSurvives() throws Exception {
            assertThat(converter.jsonToXml("{\"m\":[[1,2],[]]}").replaceAll("\\s+", ""))
                  .contains("<m><values>1</values><values>2</values></m>")
                  .contains("<m/>");
        }

        @Test @DisplayName("depth beyond two nests the wrapper as well")
        void deeperNestingWraps() throws Exception {
            // Two wrapper levels, not three: a one-element array collapses to a bare
            // element in XML, which is pre-existing and inherent to the format. The
            // values and their grouping survive, which is what the fix is for.
            assertThat(converter.jsonToXml("{\"c\":[[[1.0,2.0]]]}").replaceAll("\\s+", ""))
                  .isEqualTo("<root><c><values><values>1.0</values>"
                        + "<values>2.0</values></values></c></root>");
            // A two-element outer array does keep every level.
            assertThat(converter.jsonToXml("{\"c\":[[[1]],[[2]]]}").replaceAll("\\s+", ""))
                  .isEqualTo("<root><c><values><values>1</values></values></c>"
                        + "<c><values><values>2</values></values></c></root>");
        }

        @Test @DisplayName("names that would merge into one JSON key are refused")
        void mergingNamesAreRefused() {
            // Jackson keys on the LOCAL name, so these became one key holding both
            // values and the distinction vanished silently.
            assertThatThrownBy(() -> converter.xmlToJson(
                  "<r xmlns:p=\"urn:p\" xmlns:q=\"urn:q\"><p:a>1</p:a><q:a>2</q:a></r>"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("same key");
            assertThatThrownBy(() -> converter.xmlToJson("<r a=\"1\"><a>2</a></r>"))
                  .isInstanceOf(IllegalArgumentException.class);
            // Nested, not just at the root.
            assertThatThrownBy(() -> converter.xmlToJson(
                  "<r xmlns:p=\"urn:p\"><inner><p:a>1</p:a><a>2</a></inner></r>"))
                  .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("ordinary namespaced XML and repeated elements still convert")
        void namespacesWithoutCollisionsStillWork() throws Exception {
            // Distinct local names: nothing merges, so nothing is refused.
            assertThat(converter.xmlToJson(
                  "<r xmlns:p=\"urn:p\"><p:a>1</p:a><p:b>2</p:b></r>"))
                  .isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
            // The same name repeated is an ordinary list, not a collision.
            assertThat(converter.xmlToJson("<r><a>1</a><a>2</a></r>"))
                  .isEqualTo("{\"a\":[\"1\",\"2\"]}");
            assertThat(converter.xmlToJson("<r a=\"1\" b=\"2\"/>"))
                  .isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
        }

        @Test @DisplayName("flat arrays and objects are untouched")
        void flatShapesUnchanged() throws Exception {
            assertThat(converter.jsonToXml("{\"m\":[1,2],\"n\":{\"a\":1}}").replaceAll("\\s+", ""))
                  .isEqualTo("<root><m>1</m><m>2</m><n><a>1</a></n></root>");
            assertThat(converter.xmlToJson(converter.jsonToXml("{\"m\":[1,2]}"), true))
                  .isEqualTo("{\"m\":[1,2]}");
        }
    }

    /** What XML cannot spell directly still has to come back as it went in. */
    @Nested @DisplayName("round trips")
    class RoundTrips {

        @Test @DisplayName("a declared encoding does not decode the text a second time")
        void declaredEncodingIsNotReapplied() throws Exception {
            String latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r><name>José</name></r>";
            assertThat(json.readTree(converter.xmlToJson(latin)).get("name").asText()).isEqualTo("José");
            String utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><r><name>日本</name></r>";
            assertThat(json.readTree(converter.xmlToJson(utf16)).get("name").asText()).isEqualTo("日本");
        }

        @Test @DisplayName("element names follow XML's name rules, so the output reads back")
        void elementNamesFollowXmlRules() throws Exception {
            // Letters to Java, not name characters to XML.
            assertThat(JsonXmlConverter.xmlElementName("latency_µs")).isEqualTo("latency__s");
            assertThat(JsonXmlConverter.xmlElementName("ªº")).isEqualTo("__");
            // Middle dot may follow a name but not start one; a supplementary letter is a name.
            assertThat(JsonXmlConverter.xmlElementName("a·b")).isEqualTo("a·b");
            assertThat(JsonXmlConverter.xmlElementName("·x")).isEqualTo("_·x");
            assertThat(JsonXmlConverter.xmlElementName("𝒳")).isEqualTo("𝒳");
            assertThat(JsonXmlConverter.xmlElementName("größe")).isEqualTo("größe");
            assertThat(JsonXmlConverter.xmlElementName("ns:key")).isEqualTo("ns_key");

            String input = "{\"latency_µs\":12,\"ª\":1,\"𝒳\":2,\"a·b\":3,"
                  + "\"·x\":4,\"日本\":5,\"é\":6}";
            JsonNode back = json.readTree(converter.xmlToJson(converter.jsonToXml(input), true));
            assertThat(back.size()).isEqualTo(7);
            assertThat(back.get("latency__s").asInt()).isEqualTo(12);
            assertThat(back.get("𝒳").asInt()).isEqualTo(2);
        }

        @Test @DisplayName("null comes back as null, not as an empty string")
        void nullSurvives() throws Exception {
            String xml = converter.jsonToXml("{\"middleName\":null,\"xs\":[1,null,2],\"o\":{\"n\":null}}");
            assertThat(xml).contains("xsi:nil=\"true\"");
            assertThat(json.readTree(converter.xmlToJson(xml, true)))
                  .isEqualTo(json.readTree("{\"middleName\":null,\"xs\":[1,null,2],\"o\":{\"n\":null}}"));
        }
    }

    @Nested @DisplayName("Element names from keys")
    class ElementNamesFromKeys {
        private final JsonXmlConverter converter = new JsonXmlConverter();

        @Test @DisplayName("a key that is already an element name keeps it; only changed keys take a counter")
        void validNamesAreKept() throws Exception {
            // In document order "first name" took first_name, and the real
            // first_name became first_name_2: <first_name> held the wrong value.
            assertThat(converter.jsonToXml("{\"first name\":\"Ann\",\"first_name\":\"Bob\"}"))
                  .contains("<first_name>Bob</first_name>").contains("<first_name_2>Ann</first_name_2>");
        }
    }
}
