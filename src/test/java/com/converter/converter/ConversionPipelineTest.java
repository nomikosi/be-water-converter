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

/**
 * Tests for the UI-independent ConversionPipeline: lenient JSON input,
 * autoClose repair, and structure-preserving XML pretty-printing.
 */
@DisplayName("ConversionPipeline")
class ConversionPipelineTest {

    private ConversionPipeline pipeline;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        pipeline = new ConversionPipeline();
        json     = new ObjectMapper();
    }

    // ── Lenient JSON input ────────────────────────────────────────────────

    @Test @DisplayName("JSON input tolerates comments, trailing commas and single quotes")
    void lenientJsonInput() throws Exception {
        String messy = "{\n  // a comment\n  'name': \"Alice\",\n  \"tags\": [1, 2,],\n}";
        String strict = pipeline.normalizeToJson(messy, ConversionPipeline.FMT_JSON, true);
        JsonNode node = json.readTree(strict);
        assertThat(node.get("name").asText()).isEqualTo("Alice");
        assertThat(node.get("tags")).hasSize(2);
    }

    @Test @DisplayName("JSON input tolerates unquoted field names")
    void unquotedFieldNames() throws Exception {
        String strict = pipeline.normalizeToJson("{name: \"Bob\"}", ConversionPipeline.FMT_JSON, true);
        assertThat(json.readTree(strict).get("name").asText()).isEqualTo("Bob");
    }

    // ── autoClose ─────────────────────────────────────────────────────────

    @Test @DisplayName("autoClose repairs an unterminated string and brackets")
    void autoCloseUnterminatedString() throws Exception {
        String repaired = pipeline.autoClose("{\"name\": \"Al");
        assertThat(repaired).isEqualTo("{\"name\": \"Al\"}");
        json.readTree(repaired); // must parse
    }

    @Test @DisplayName("autoClose repairs unclosed brackets")
    void autoCloseBrackets() {
        assertThat(pipeline.autoClose("{\"a\": [1, 2")).isEqualTo("{\"a\": [1, 2]}");
    }

    @Test @DisplayName("autoClose repairs a dangling escape into parseable JSON")
    void autoCloseDanglingEscape() throws Exception {
        json.readTree(pipeline.autoClose("{\"path\": \"C:\\"));
    }

    @Test @DisplayName("autoClose leaves complete JSON untouched")
    void autoCloseNoOp() {
        String complete = "{\"a\": [1, 2]}";
        assertThat(pipeline.autoClose(complete)).isEqualTo(complete);
    }

    // ── prettyXml ─────────────────────────────────────────────────────────

    @Test @DisplayName("prettyXml preserves the original root element and attributes")
    void prettyXmlPreservesRoot() throws Exception {
        String result = pipeline.prettyXml(
              "<person id=\"7\"><name>Ada</name><langs><l>en</l><l>el</l></langs></person>");
        assertThat(result).startsWith("<person id=\"7\">")
              .contains("  <name>Ada</name>")
              .contains("<langs>");
    }

    @Test @DisplayName("prettyXml keeps the XML declaration only when the input had one")
    void prettyXmlDeclaration() throws Exception {
        assertThat(pipeline.prettyXml("<?xml version=\"1.0\"?><r><a>1</a></r>"))
              .startsWith("<?xml");
        assertThat(pipeline.prettyXml("<r><a>1</a></r>"))
              .doesNotContain("<?xml");
    }

    @Test @DisplayName("prettyXml rejects DOCTYPE declarations (XXE hardening)")
    void prettyXmlRejectsDoctype() {
        assertThatThrownBy(() -> pipeline.prettyXml(
              "<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><foo>&x;</foo>"))
              .isInstanceOf(Exception.class);
    }

    // ── formatInput dispatch ──────────────────────────────────────────────

    @Test @DisplayName("formatInput pretty-prints truncated JSON via autoClose")
    void formatInputJson() throws Exception {
        String result = pipeline.formatInput("{\"a\":1", ConversionPipeline.FMT_JSON, true);
        assertThat(json.readTree(result).get("a").intValue()).isEqualTo(1);
    }

    @Test @DisplayName("formatInput collapses excess blank lines in proto schemas")
    void formatInputProto() throws Exception {
        String result = pipeline.formatInput(
              "message A {\n  string x = 1;   \n\n\n\n}", ConversionPipeline.FMT_PROTO, true);
        assertThat(result).doesNotContain("\n\n\n");
    }
}
