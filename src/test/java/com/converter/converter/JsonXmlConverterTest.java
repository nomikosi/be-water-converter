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
}
