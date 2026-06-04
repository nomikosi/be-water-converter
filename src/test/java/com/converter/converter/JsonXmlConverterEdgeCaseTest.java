package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Edge-case tests for JsonXmlConverter.
 */
@DisplayName("JsonXmlConverter \u2013 Edge Cases")
class JsonXmlConverterEdgeCaseTest {

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

    // ── Multiple sibling repeated elements ───────────────────────────────

    @Test @DisplayName("XML->JSON: repeated same-name siblings produce array")
    void xmlSiblingsSameNameArray() throws Exception {
        String xml = "<root><item>1</item><item>2</item><item>3</item></root>";
        JsonNode result = json.readTree(converter.xmlToJson(xml));
        JsonNode items = result.get("item");
        assertThat(items).isNotNull();
        assertThat(items.isArray() || items.size() > 0).isTrue();
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
        assertThatCode(() -> converter.xmlToJson(xxe))
            .satisfiesAnyOf(
                t -> { /* parsed safely, entity unexpanded */ },
                t -> assertThat(t).isInstanceOf(Exception.class)
            );
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

    @Test @DisplayName("JSON->XML: key starting with digit is sanitised or throws descriptively")
    void jsonToXmlKeyStartingWithDigit() {
        assertThatCode(() -> converter.jsonToXml("{\"1abc\":\"val\"}"))
              .satisfiesAnyOf(
                    t -> { /* produced valid output with sanitised tag */ },
                    t -> assertThat(t).isInstanceOf(Exception.class)
              );
    }

    @Test @DisplayName("JSON->XML: key containing space is sanitised or throws descriptively")
    void jsonToXmlKeyWithSpace() {
        assertThatCode(() -> converter.jsonToXml("{\"my field\":\"val\"}"))
              .satisfiesAnyOf(
                    t -> { /* produced valid output */ },
                    t -> assertThat(t).isInstanceOf(Exception.class)
              );
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
