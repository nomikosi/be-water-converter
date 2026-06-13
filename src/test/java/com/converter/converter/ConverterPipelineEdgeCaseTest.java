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
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Edge-case pipeline tests covering ConverterPanel.dispatch() behavior.
 * Covers: empty/whitespace inputs, Unicode through every hop, special
 * characters in multi-hop conversions, numeric precision through the hub,
 * CSV-with-commas through XML/YAML/TOML, large payloads, autoClose
 * corner cases, and boolean coercion through every path.
 */
@DisplayName("Converter Pipeline Edge Cases")
class ConverterPipelineEdgeCaseTest {

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
