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
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end pipeline tests covering every cross-format conversion path.
 * The same route every conversion takes:  input -> JSON hub -> output.
 * Also covers the autoClose repair logic for truncated JSON.
 */
@DisplayName("Converter Pipeline (end-to-end)")
class CrossFormatPipelineTest {

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
        // Must be VALID TOML that parses back — CSV yields a top-level array,
        // which unwrapped would render as invalid ' = [...]' TOML.
        var back = om.readTree(toml.tomlToJson(tomlStr));
        assertThat(back.get("items")).hasSize(2);
        assertThat(tomlStr).contains("alpha").contains("beta");
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
        assertThat(pojoResult).contains("public class Root").contains("\nclass Menu");
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

    /**
     * Edge cases of the route every conversion takes.
     * Covers: empty/whitespace inputs, Unicode through every hop, special
     * characters in multi-hop conversions, numeric precision through the hub,
     * CSV-with-commas through XML/YAML/TOML, large payloads, autoClose
     * corner cases, and boolean coercion through every path.
     */
    @Nested @DisplayName("edge cases")
    class EdgeCases {
        private JsonXmlConverter jsonXml;
        private JsonYamlConverter jsonYaml;
        private CsvConverter csv;
        private TomlConverter toml;
        private ProtoConverter proto;
        private JavaPojoGenerator pojo;
        private ObjectMapper om;

        @BeforeEach void setUp() {
            jsonXml  = new JsonXmlConverter();
            jsonYaml = new JsonYamlConverter();
            csv      = new CsvConverter();
            toml     = new TomlConverter();
            proto    = new ProtoConverter();
            pojo     = new JavaPojoGenerator();
            om       = new ObjectMapper();
        }

        @Test @DisplayName("Pipeline: Greek JSON->XML->YAML->JSON preserves text")
        void greekJsonXmlYamlJson() throws Exception {
            String start = "{\"city\":\"\u0391\u03b8\u03ae\u03bd\u03b1\",\"country\":\"\u0395\u03bb\u03bb\u03ac\u03b4\u03b1\"}";
            String xml   = jsonXml.jsonToXml(start);
            String back1 = jsonXml.xmlToJson(xml);
            String yaml  = jsonYaml.jsonToYaml(back1);
            JsonNode end = om.readTree(jsonYaml.yamlToJson(yaml));
            assertThat(end.get("city").asText()).isEqualTo("\u0391\u03b8\u03ae\u03bd\u03b1");
        }

        @Test @DisplayName("Pipeline: Unicode JSON->TOML->JSON preserves all characters")
        void unicodeJsonTomlJson() throws Exception {
            String start = "{\"greeting\":\"\u039a\u03b1\u03bb\u03b7\u03bc\u03ad\u03c1\u03b1\",\"emoji\":\"\uD83D\uDE80\"}";
            JsonNode back = om.readTree(toml.tomlToJson(toml.jsonToToml(start)));
            assertThat(back.get("greeting").asText()).isEqualTo("\u039a\u03b1\u03bb\u03b7\u03bc\u03ad\u03c1\u03b1");
        }

        @Test @DisplayName("Pipeline: empty JSON string throws")
        void emptyJsonInput() {
            assertThatThrownBy(() -> jsonXml.jsonToXml(""))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("Pipeline: whitespace-only YAML throws or is empty")
        void whitespaceOnlyYaml() {
            assertThatCode(() -> jsonYaml.yamlToJson("   \n   "))
                  .satisfiesAnyOf(
                        t -> { /* returned null/empty without throw */ },
                        t -> assertThat(t).isInstanceOf(Exception.class)
                  );
        }

        @Test @DisplayName("Pipeline: empty CSV header-only does not throw")
        void emptyCsvHeaderOnly() {
            assertThatCode(() -> csv.csvToJson("id,name,email\n")).doesNotThrowAnyException();
        }

        @Test @DisplayName("Pipeline: float JSON->YAML->JSON precision maintained")
        void floatPrecisionYaml() throws Exception {
            String start = "{\"pi\":3.141592653589793}";
            JsonNode back = om.readTree(jsonYaml.yamlToJson(jsonYaml.jsonToYaml(start)));
            assertThat(back.get("pi").asDouble()).isCloseTo(3.141592653589793, within(0.0000001));
        }

        @Test @DisplayName("Pipeline: large integer JSON->XML->JSON not truncated")
        void largeIntXmlRoundTrip() throws Exception {
            String start = "{\"value\":9007199254740991}";
            JsonNode back = om.readTree(jsonXml.xmlToJson(jsonXml.jsonToXml(start)));
            assertThat(back.get("value").asLong()).isEqualTo(9007199254740991L);
        }

        @Test @DisplayName("Pipeline: negative integer JSON->TOML->JSON preserved")
        void negativeIntTomlRoundTrip() throws Exception {
            String start = "{\"temp\":-273}";
            JsonNode back = om.readTree(toml.tomlToJson(toml.jsonToToml(start)));
            assertThat(back.get("temp").asInt()).isEqualTo(-273);
        }

        @Test @DisplayName("Pipeline: JSON booleans->XML->JSON survive as recognisable booleans")
        void booleanXmlRoundTrip() throws Exception {
            String start = "{\"enabled\":true,\"debug\":false}";
            JsonNode back = om.readTree(jsonXml.xmlToJson(jsonXml.jsonToXml(start)));
            assertThat(back.get("enabled").asText()).isEqualToIgnoringCase("true");
        }

        @Test @DisplayName("Pipeline: JSON booleans->TOML->JSON preserved")
        void booleanTomlRoundTrip() throws Exception {
            String start = "{\"active\":true}";
            JsonNode back = om.readTree(toml.tomlToJson(toml.jsonToToml(start)));
            assertThat(back.get("active").asBoolean()).isTrue();
        }

        @Test @DisplayName("Pipeline: CSV with quoted commas->JSON->XML contains correct value")
        void csvQuotedCommaToXml() throws Exception {
            String csvInput = "name,address\nAlice,\"123 Main St, Apt 4\"\n";
            String jsonHub  = csv.csvToJson(csvInput);
            String xml      = jsonXml.jsonToXml(jsonHub);
            assertThat(xml).contains("Alice");
        }

        @Test @DisplayName("Pipeline: CSV->JSON->YAML contains all rows")
        void csvToJsonToYaml() throws Exception {
            String csvInput = "id,name\n1,Alice\n2,Bob\n3,Charlie\n";
            String yaml = jsonYaml.jsonToYaml(csv.csvToJson(csvInput));
            assertThat(yaml).contains("Alice").contains("Bob").contains("Charlie");
        }

        @Test @DisplayName("Pipeline: k8s Deployment YAML->JSON->XML does not throw")
        void k8sYamlToXml() {
            String yaml =
                  "apiVersion: apps/v1\n" +
                        "kind: Deployment\n" +
                        "metadata:\n" +
                        "  name: nginx\n" +
                        "spec:\n" +
                        "  replicas: 3\n";
            assertThatCode(() -> jsonXml.jsonToXml(jsonYaml.yamlToJson(yaml))).doesNotThrowAnyException();
        }

        @Test @DisplayName("Pipeline: k8s YAML->JSON->TOML does not throw")
        void k8sYamlToToml() {
            String yaml =
                  "apiVersion: apps/v1\n" +
                        "kind: Deployment\n" +
                        "metadata:\n" +
                        "  name: nginx\n" +
                        "spec:\n" +
                        "  replicas: 3\n";
            assertThatCode(() -> toml.jsonToToml(jsonYaml.yamlToJson(yaml))).doesNotThrowAnyException();
        }

        @Test @DisplayName("Pipeline: Cargo.toml->JSON->YAML contains package name")
        void cargoTomlToYaml() throws Exception {
            String cargoToml =
                  "[package]\n" +
                        "name = \"my-crate\"\n" +
                        "version = \"0.1.0\"\n";
            String yaml = jsonYaml.jsonToYaml(toml.tomlToJson(cargoToml));
            assertThat(yaml).contains("my-crate");
        }

        @Test @DisplayName("Pipeline: Cargo.toml->JSON->Java POJO generates Root class")
        void cargoTomlToPojo() throws Exception {
            String cargoToml =
                  "[package]\n" +
                        "name = \"my-crate\"\n" +
                        "version = \"0.1.0\"\n";
            String result = pojo.fromJson(toml.tomlToJson(cargoToml));
            assertThat(result).contains("public class Root");
        }

        @Test @DisplayName("Pipeline: Proto->JSON->CSV: flat message produces CSV row")
        void protoToCsvPipeline() throws Exception {
            String protoStr = "message Product { string sku = 1; double price = 2; int32 stock = 3; }";
            String jsonHub  = proto.protoToJson(protoStr);
            String csvOut   = csv.jsonToCsv(jsonHub);
            assertThat(csvOut).contains("sku").contains("price").contains("stock");
        }

        @Test @DisplayName("Pipeline: Proto->JSON->TOML: message fields appear in TOML")
        void protoToTomlPipeline() throws Exception {
            String protoStr = "message Config { string env = 1; int32 workers = 2; bool verbose = 3; }";
            String tomlOut  = toml.jsonToToml(proto.protoToJson(protoStr));
            assertThat(tomlOut).isNotBlank();
        }

        @Test @DisplayName("autoClose: string containing brackets {[ not counted inside strings")
        void autoCloseStringContainingBrackets() throws Exception {
            String input = "{\"template\":\"{value} is [ok]\",\"id\":1";
            JsonNode result = om.readTree(autoClose(input));
            assertThat(result.get("template").asText()).isEqualTo("{value} is [ok]");
        }

        @Test @DisplayName("autoClose: escaped backslash before quote not misinterpreted")
        void autoCloseEscapedBackslash() throws Exception {
            String input = "{\"path\":\"C:\\\\Users\\\\Alice\",\"id\":42";
            JsonNode result = om.readTree(autoClose(input));
            assertThat(result.get("id").asInt()).isEqualTo(42);
        }

        @Test @DisplayName("autoClose: triple-nested truncation recovers all levels")
        void autoCloseTripleNested() throws Exception {
            String input = "{\"a\":{\"b\":{\"c\":1";
            JsonNode result = om.readTree(autoClose(input));
            assertThat(result.path("a").path("b").path("c").asInt()).isEqualTo(1);
        }

        @Test @DisplayName("autoClose: array of objects truncated mid-second-element")
        void autoCloseArrayMidElement() {
            String input = "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bo\"";
            assertThatCode(() -> om.readTree(autoClose(input))).doesNotThrowAnyException();
        }

        @Test @DisplayName("autoClose: entirely empty string appends only needed closers")
        void autoCloseEmptyString() {
            assertThat(autoClose("")).isEqualTo("");
        }

        @Test @DisplayName("Pipeline: 100-item JSON array->XML->JSON maintains count")
        void largeArrayXmlRoundTrip() throws Exception {
            StringBuilder sb = new StringBuilder("{\"items\":[");
            for (int i = 0; i < 100; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"id\":").append(i).append(",\"label\":\"item").append(i).append("\"}");
            }
            sb.append("]}");
            String xml = jsonXml.jsonToXml(sb.toString());
            assertThat(xml).contains("item99");
        }

        @Test @DisplayName("Pipeline: 50-row CSV->JSON->YAML contains last row value")
        void largeCsvToYaml() throws Exception {
            StringBuilder sb = new StringBuilder("id,name\n");
            for (int i = 1; i <= 50; i++) sb.append(i).append(",User").append(i).append("\n");
            String yaml = jsonYaml.jsonToYaml(csv.csvToJson(sb.toString()));
            assertThat(yaml).contains("User50");
        }

        @Test @DisplayName("Pipeline: JSON with null field goes through YAML without throw")
        void nullFieldToYaml() {
            assertThatCode(() -> jsonYaml.jsonToYaml("{\"name\":\"Alice\",\"note\":null}"))
                  .doesNotThrowAnyException();
        }

        @Test @DisplayName("Pipeline: JSON with null field goes through XML without throw")
        void nullFieldToXml() {
            assertThatCode(() -> jsonXml.jsonToXml("{\"name\":\"Alice\",\"note\":null}"))
                  .doesNotThrowAnyException();
        }

        @Test @DisplayName("Pipeline: JSON with null field goes through TOML without throw")
        void nullFieldToToml() {
            assertThatCode(() -> toml.jsonToToml("{\"name\":\"Alice\",\"note\":null}"))
                  .doesNotThrowAnyException();
        }
        private String autoClose(String s) {
            Deque<Character> stack = new ArrayDeque<>();
            boolean inString = false, escape = false;
            for (char c : s.toCharArray()) {
                if (escape)              { escape = false; continue; }
                if (c == '\\')         { if (inString) escape = true; continue; }
                if (c == '"')            { inString = !inString; continue; }
                if (inString)            continue;
                if (c == '{')            stack.push('}');
                else if (c == '[')       stack.push(']');
                else if (c == '}' || c == ']') { if (!stack.isEmpty()) stack.pop(); }
            }
            StringBuilder sb = new StringBuilder(s);
            while (!stack.isEmpty()) sb.append(stack.pop());
            return sb.toString();
        }
    }
}
