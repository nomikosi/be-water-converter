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
 * End-to-end pipeline tests covering every cross-format conversion path.
 * Mirrors what ConverterPanel.dispatch() does:  input -> JSON hub -> output.
 * Also covers the autoClose repair logic for truncated JSON.
 */
@DisplayName("Converter Pipeline (end-to-end)")
class ConverterPipelineTest {

    private JsonXmlConverter  jsonXml;
    private JsonYamlConverter jsonYaml;
    private CsvConverter      csv;
    private TomlConverter     toml;
    private ProtoConverter    proto;
    private JavaPojoGenerator pojo;
    private ObjectMapper      om;

    @BeforeEach void setUp() {
        jsonXml  = new JsonXmlConverter();
        jsonYaml = new JsonYamlConverter();
        csv      = new CsvConverter();
        toml     = new TomlConverter();
        proto    = new ProtoConverter();
        pojo     = new JavaPojoGenerator();
        om       = new ObjectMapper();
    }

    // ── Round-trips ───────────────────────────────────────────────────────

    @Test @DisplayName("JSON->XML->JSON round-trip")
    void jsonXmlJson() throws Exception {
        String original = "{\"city\":\"Athens\",\"population\":3153000}";
        JsonNode back = om.readTree(jsonXml.xmlToJson(jsonXml.jsonToXml(original)));
        assertThat(back.get("city").asText()).isEqualTo("Athens");
    }

    @Test @DisplayName("JSON->YAML->JSON round-trip")
    void jsonYamlJson() throws Exception {
        String original = "{\"service\":\"api\",\"port\":8080}";
        JsonNode back = om.readTree(jsonYaml.yamlToJson(jsonYaml.jsonToYaml(original)));
        assertThat(back.get("port").asInt()).isEqualTo(8080);
    }

    @Test @DisplayName("JSON->CSV->JSON round-trip")
    void jsonCsvJson() throws Exception {
        String original = "[{\"id\":\"1\",\"name\":\"Alice\"},{\"id\":\"2\",\"name\":\"Bob\"}]";
        JsonNode back = om.readTree(csv.csvToJson(csv.jsonToCsv(original)));
        assertThat(back.isArray()).isTrue();
        assertThat(back.size()).isEqualTo(2);
        assertThat(back.get(0).get("name").asText()).isEqualTo("Alice");
    }

    @Test @DisplayName("JSON->TOML->JSON round-trip")
    void jsonTomlJson() throws Exception {
        String original = "{\"db\":{\"host\":\"localhost\",\"port\":5432}}";
        JsonNode back = om.readTree(toml.tomlToJson(toml.jsonToToml(original)));
        assertThat(back.path("db").path("port").asInt()).isEqualTo(5432);
    }

    // ── Cross-format paths ────────────────────────────────────────────────

    @Test @DisplayName("YAML->XML: via JSON hub")
    void yamlToXml() throws Exception {
        String xml = jsonXml.jsonToXml(jsonYaml.yamlToJson("name: Alice\nage: 30\n"));
        assertThat(xml).contains("<name>Alice</name>").contains("<age>30</age>");
    }

    @Test @DisplayName("XML->YAML: via JSON hub")
    void xmlToYaml() throws Exception {
        String yaml = jsonYaml.jsonToYaml(jsonXml.xmlToJson("<root><host>db.local</host><port>5432</port></root>"));
        assertThat(yaml).contains("host: db.local");
    }

    @Test @DisplayName("CSV->YAML: via JSON hub")
    void csvToYaml() throws Exception {
        String yaml = jsonYaml.jsonToYaml(csv.csvToJson("id,name\n1,Alice\n2,Bob\n"));
        assertThat(yaml).contains("name: Alice").contains("name: Bob");
    }

    @Test @DisplayName("CSV->XML: via JSON hub")
    void csvToXml() throws Exception {
        String xml = jsonXml.jsonToXml(csv.csvToJson("product,price\nWidget,9.99\nGadget,14.99\n"));
        assertThat(xml).contains("Widget").contains("Gadget");
    }

    @Test @DisplayName("CSV->TOML: via JSON hub")
    void csvToToml() throws Exception {
        String tomlStr = toml.jsonToToml(csv.csvToJson("key,value\nalpha,1\nbeta,2\n"));
        assertThat(tomlStr).isNotBlank();
    }

    @Test @DisplayName("TOML->YAML: via JSON hub")
    void tomlToYaml() throws Exception {
        String yaml = jsonYaml.jsonToYaml(toml.tomlToJson("title = \"Config\"\n[server]\nport = 9090\n"));
        assertThat(yaml).contains("title: Config").contains("port: 9090");
    }

    @Test @DisplayName("TOML->XML: via JSON hub")
    void tomlToXml() throws Exception {
        String xml = jsonXml.jsonToXml(toml.tomlToJson("name = \"Alice\"\nage = 30\n"));
        assertThat(xml).contains("<name>Alice</name>");
    }

    @Test @DisplayName("TOML->CSV: via JSON hub")
    void tomlToCsv() throws Exception {
        String csvStr = csv.jsonToCsv(toml.tomlToJson("[[items]]\nid = 1\nname = \"A\"\n[[items]]\nid = 2\nname = \"B\"\n"));
        assertThat(csvStr).contains("id").contains("name");
    }

    @Test @DisplayName("Proto->YAML: via JSON hub")
    void protoToYaml() throws Exception {
        String yaml = jsonYaml.jsonToYaml(proto.protoToJson("message Config { string env = 1; int32 workers = 2; }"));
        assertThat(yaml).contains("Config:").contains("env:");
    }

    @Test @DisplayName("Proto->XML: via JSON hub")
    void protoToXml() throws Exception {
        String xml = jsonXml.jsonToXml(proto.protoToJson("message User { string name = 1; int32 id = 2; }"));
        assertThat(xml).contains("<name>");
    }

    @Test @DisplayName("Proto->CSV: via JSON hub (flat message)")
    void protoCsv() throws Exception {
        String jsonStr = proto.protoToJson("message Row { string label = 1; int32 value = 2; }");
        String csvStr  = csv.jsonToCsv(jsonStr);
        assertThat(csvStr).isNotBlank();
    }

    // ── YAML/XML/CSV/TOML -> Java POJO ────────────────────────────────────

    @Test @DisplayName("YAML->Java POJO: via JSON hub")
    void yamlToPojo() throws Exception {
        String result = pojo.fromJson(jsonYaml.yamlToJson("id: 1\nname: Alice\nactive: true\n"));
        assertThat(result).contains("public class Root")
            .contains("private Integer id").contains("private String name").contains("private Boolean active");
    }

    @Test @DisplayName("XML->Java POJO: via JSON hub")
    void xmlToPojo() throws Exception {
        String result = pojo.fromJson(jsonXml.xmlToJson("<root><product>Widget</product><price>9.99</price></root>"));
        assertThat(result).contains("public class Root").contains("product");
    }

    @Test @DisplayName("CSV->Java POJO: via JSON hub")
    void csvToPojo() throws Exception {
        String result = pojo.fromJson(csv.csvToJson("id,name,score\n1,Alice,95\n"));
        assertThat(result).contains("public class Root").contains("name").contains("score");
    }

    @Test @DisplayName("TOML->Java POJO: via JSON hub")
    void tomlToPojo() throws Exception {
        String result = pojo.fromJson(toml.tomlToJson("name = \"Alice\"\nage = 30\n"));
        assertThat(result).contains("public class Root").contains("private String name");
    }

    @Test @DisplayName("Proto->Java POJO: via JSON hub")
    void protoToPojo() throws Exception {
        String result = pojo.fromJson(proto.protoToJson("message User { string name = 1; int32 id = 2; bool active = 3; }"));
        assertThat(result).contains("public class").contains("name").contains("active");
    }

    // ── autoClose ─────────────────────────────────────────────────────────

    @Test @DisplayName("autoClose: missing closing brace")
    void autoCloseBrace() throws Exception {
        JsonNode result = om.readTree(autoClose("{\"name\":\"Alice\",\"age\":30"));
        assertThat(result.get("name").asText()).isEqualTo("Alice");
    }

    @Test @DisplayName("autoClose: missing closing bracket")
    void autoCloseBracket() throws Exception {
        JsonNode result = om.readTree(autoClose("[{\"id\":1,\"name\":\"Alice\"}"));
        assertThat(result.isArray()).isTrue();
        assertThat(result.get(0).get("name").asText()).isEqualTo("Alice");
    }

    @Test @DisplayName("autoClose: deeply truncated nested JSON parses")
    void autoCloseDeep() {
        String truncated = "[{\n  \"menu\" : {\n    \"id\" : \"file\",\n    \"value\" : \"File\"";
        assertThatCode(() -> om.readTree(autoClose(truncated))).doesNotThrowAnyException();
    }

    @Test @DisplayName("autoClose: already-complete JSON unchanged")
    void autoCloseNoop() {
        String complete = "{\"a\":1,\"b\":[1,2,3]}";
        assertThat(autoClose(complete)).isEqualTo(complete);
    }

    @Test @DisplayName("autoClose: truncated JSON works through all converters")
    void autoCloseAllPaths() throws Exception {
        String fixed = autoClose("[{\"id\":1,\"name\":\"Alice\",\"score\":95.5}");
        assertThatCode(() -> jsonXml.jsonToXml(fixed)).doesNotThrowAnyException();
        assertThatCode(() -> jsonYaml.jsonToYaml(fixed)).doesNotThrowAnyException();
        assertThatCode(() -> csv.jsonToCsv(fixed)).doesNotThrowAnyException();
        assertThatCode(() -> toml.jsonToToml(fixed)).doesNotThrowAnyException();
        assertThatCode(() -> proto.jsonToProto(fixed)).doesNotThrowAnyException();
        assertThatCode(() -> pojo.fromJson(fixed)).doesNotThrowAnyException();
    }

    @Test @DisplayName("autoClose: exact bug report input parses and converts")
    void autoCloseBugReport() throws Exception {
        // The exact truncated JSON from the bug report — missing closing ] 
        String input = "[{\n  \"menu\" : {\n    \"id\" : \"file\",\n    \"value\" : \"File\",\n    \"popup\" : {\n      \"menuitem\" : [ {\n        \"value\" : \"New\",\n        \"onclick\" : \"CreateNewDoc()\"\n      }, {\n        \"value\" : \"Open\",\n        \"onclick\" : \"OpenDoc()\"\n      }, {\n        \"value\" : \"Close\",\n        \"onclick\" : \"CloseDoc()\"\n      } ]\n    }\n  }";
        String fixed = autoClose(input);
        String pojoResult = pojo.fromJson(fixed);
        assertThat(pojoResult).contains("public class Root").contains("public class Menu");
        String protoResult = proto.jsonToProto(fixed);
        assertThat(protoResult).contains("syntax = \"proto3\"").contains("message Root");
    }

    // ── inline autoClose (mirrors ConverterPanel) ─────────────────────────
    private String autoClose(String s) {
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false, escape = false;
        for (char c : s.toCharArray()) {
            if (escape)        { escape = false; continue; }
            if (c == '\\')   { if (inString) escape = true; continue; }
            if (c == '"')      { inString = !inString; continue; }
            if (inString)      continue;
            if (c == '{')      stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') { if (!stack.isEmpty()) stack.pop(); }
        }
        StringBuilder sb = new StringBuilder(s);
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
    }
}
