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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonYamlConverter")
class JsonYamlConverterTest {

    private JsonYamlConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new JsonYamlConverter();
        json = new ObjectMapper();
    }

    // ── JSON -> YAML ──────────────────────────────────────────────────────

    @Test @DisplayName("JSON->YAML: flat object produces key: value lines")
    void jsonToYamlFlat() throws Exception {
        String result = converter.jsonToYaml("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result).contains("name: Alice").contains("age: 30");
    }

    @Test @DisplayName("JSON->YAML: nested object produces indented YAML")
    void jsonToYamlNested() throws Exception {
        String result = converter.jsonToYaml("{\"server\":{\"host\":\"localhost\",\"port\":8080}}");
        assertThat(result).contains("server:").contains("host: localhost").contains("port: 8080");
    }

    @Test @DisplayName("JSON->YAML: array of strings")
    void jsonToYamlArrayStrings() throws Exception {
        String result = converter.jsonToYaml("{\"colors\":[\"red\",\"green\",\"blue\"]}");
        assertThat(result).contains("colors:").contains("- red").contains("- green");
    }

    @Test @DisplayName("JSON->YAML: array of objects")
    void jsonToYamlArrayObjects() throws Exception {
        String result = converter.jsonToYaml("{\"users\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]}");
        assertThat(result).contains("- id: 1").contains("name: Alice");
    }

    @Test @DisplayName("JSON->YAML: boolean and numeric values")
    void jsonToYamlBoolNum() throws Exception {
        String result = converter.jsonToYaml("{\"enabled\":true,\"debug\":false,\"timeout\":30}");
        assertThat(result).contains("enabled: true").contains("debug: false").contains("timeout: 30");
    }

    @Test @DisplayName("JSON->YAML: complex nested config-like structure")
    void jsonToYamlComplex() throws Exception {
        String input = "{\"app\":{\"name\":\"MyApp\",\"database\":{\"host\":\"db.local\",\"port\":5432},\"features\":[\"auth\",\"logging\"]}}";
        String result = converter.jsonToYaml(input);
        assertThat(result).contains("name: MyApp").contains("port: 5432").contains("- auth");
    }

    // ── YAML -> JSON ──────────────────────────────────────────────────────

    @Test @DisplayName("YAML->JSON: simple key-value pairs")
    void yamlToJsonSimple() throws Exception {
        JsonNode result = json.readTree(converter.yamlToJson("name: Alice\nage: 30\n"));
        assertThat(result.get("name").asText()).isEqualTo("Alice");
        assertThat(result.get("age").asInt()).isEqualTo(30);
    }

    @Test @DisplayName("YAML->JSON: nested YAML block")
    void yamlToJsonNested() throws Exception {
        JsonNode result = json.readTree(converter.yamlToJson("server:\n  host: localhost\n  port: 8080\n"));
        assertThat(result.path("server").path("host").asText()).isEqualTo("localhost");
        assertThat(result.path("server").path("port").asInt()).isEqualTo(8080);
    }

    @Test @DisplayName("YAML->JSON: YAML list becomes JSON array")
    void yamlToJsonList() throws Exception {
        JsonNode result = json.readTree(converter.yamlToJson("tags:\n  - java\n  - spring\n  - postgres\n"));
        assertThat(result.get("tags").isArray()).isTrue();
        assertThat(result.get("tags").get(0).asText()).isEqualTo("java");
    }

    @Test @DisplayName("YAML->JSON: round-trip preserves structure")
    void yamlRoundTrip() throws Exception {
        String original = "{\"service\":{\"name\":\"api\",\"replicas\":3}}";
        JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
        assertThat(back.path("service").path("replicas").asInt()).isEqualTo(3);
    }

    /**
     * Edge-case tests for JsonYamlConverter.
     */
    @Nested @DisplayName("edge cases")
    class EdgeCases {
        private JsonYamlConverter converter;
        private ObjectMapper json;

        @BeforeEach void setUp() {
            converter = new JsonYamlConverter();
            json = new ObjectMapper();
        }

        // ── Null representations ──────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: tilde null becomes JSON null")
        void yamlTildeNull() throws Exception {
            JsonNode result = json.readTree(converter.yamlToJson("value: ~\n"));
            assertThat(result.get("value").isNull()).isTrue();
        }

        @Test @DisplayName("YAML->JSON: explicit 'null' string becomes JSON null")
        void yamlExplicitNull() throws Exception {
            JsonNode result = json.readTree(converter.yamlToJson("value: null\n"));
            assertThat(result.get("value").isNull()).isTrue();
        }

        // ── Boolean-like strings ──────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: 'yes'/'no' parsed as booleans or strings without error")
        void yamlYesNo() throws Exception {
            assertThatCode(() -> converter.yamlToJson("enabled: yes\ndisabled: no\n"))
                .doesNotThrowAnyException();
        }

        @Test @DisplayName("YAML->JSON: 'on'/'off' parsed without error")
        void yamlOnOff() throws Exception {
            assertThatCode(() -> converter.yamlToJson("power: on\nlight: off\n"))
                .doesNotThrowAnyException();
        }

        // ── Empty structures ──────────────────────────────────────────────────

        @Test @DisplayName("JSON->YAML: empty object {}")
        void jsonToYamlEmptyObject() throws Exception {
            String result = converter.jsonToYaml("{}");
            assertThat(result).isNotNull();
        }

        @Test @DisplayName("JSON->YAML: empty array []")
        void jsonToYamlEmptyArray() throws Exception {
            String result = converter.jsonToYaml("[]");
            assertThat(result).isNotNull();
        }

        @Test @DisplayName("JSON->YAML: object with empty string value")
        void jsonToYamlEmptyStringValue() throws Exception {
            String result = converter.jsonToYaml("{\"key\":\"\"}");
            assertThat(result).contains("key");
        }

        @Test @DisplayName("JSON->YAML: object with null value")
        void jsonToYamlNullValue() throws Exception {
            String result = converter.jsonToYaml("{\"key\":null}");
            assertThat(result).contains("key");
        }

        // ── Unicode ───────────────────────────────────────────────────────────

        @Test @DisplayName("JSON->YAML->JSON: Greek text round-trip")
        void unicodeGreekRoundTrip() throws Exception {
            String original = "{\"city\":\"\u0391\u03b8\u03ae\u03bd\u03b1\",\"pop\":3153000}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("city").asText()).isEqualTo("\u0391\u03b8\u03ae\u03bd\u03b1");
        }

        @Test @DisplayName("JSON->YAML->JSON: CJK characters round-trip")
        void unicodeCjkRoundTrip() throws Exception {
            String original = "{\"name\":\"\u7530\u4e2d\u592a\u90ce\",\"lang\":\"\u65e5\u672c\u8a9e\"}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("name").asText()).isEqualTo("\u7530\u4e2d\u592a\u90ce");
        }

        // ── Special characters in values ─────────────────────────────────────

        @Test @DisplayName("JSON->YAML->JSON: colon in string value round-trip")
        void colonInValue() throws Exception {
            String original = "{\"url\":\"https://example.com:8080/path\"}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("url").asText()).isEqualTo("https://example.com:8080/path");
        }

        @Test @DisplayName("JSON->YAML->JSON: hash in string value round-trip")
        void hashInValue() throws Exception {
            String original = "{\"color\":\"#FF5733\",\"tag\":\"feature#42\"}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("color").asText()).isEqualTo("#FF5733");
        }

        @Test @DisplayName("JSON->YAML->JSON: newline in string value survives")
        void newlineInValue() throws Exception {
            String original = "{\"text\":\"line1\\nline2\"}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("text").asText()).contains("line1");
        }

        // ── Deeply nested ─────────────────────────────────────────────────────

        @Test @DisplayName("JSON->YAML->JSON: 5-level deep nesting round-trip")
        void deepNestedRoundTrip() throws Exception {
            String original = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":\"leaf\"}}}}}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.path("a").path("b").path("c").path("d").path("e").asText()).isEqualTo("leaf");
        }

        // ── Kubernetes-style YAML ─────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: Kubernetes Deployment YAML parses correctly")
        void kubernetesDeploymentYaml() throws Exception {
            String yaml =
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: nginx-deployment\n" +
                "  labels:\n" +
                "    app: nginx\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app: nginx\n";
            JsonNode result = json.readTree(converter.yamlToJson(yaml));
            assertThat(result.get("kind").asText()).isEqualTo("Deployment");
            assertThat(result.path("spec").path("replicas").asInt()).isEqualTo(3);
            assertThat(result.path("metadata").path("name").asText()).isEqualTo("nginx-deployment");
        }

        @Test @DisplayName("YAML->JSON: docker-compose style YAML parses correctly")
        void dockerComposeYaml() throws Exception {
            String yaml =
                "version: '3.8'\n" +
                "services:\n" +
                "  web:\n" +
                "    image: nginx:alpine\n" +
                "    ports:\n" +
                "      - '80:80'\n" +
                "  db:\n" +
                "    image: postgres:15\n" +
                "    environment:\n" +
                "      POSTGRES_DB: mydb\n" +
                "      POSTGRES_USER: admin\n";
            JsonNode result = json.readTree(converter.yamlToJson(yaml));
            assertThat(result.path("services").path("web").path("image").asText()).isEqualTo("nginx:alpine");
            assertThat(result.path("services").path("db").path("environment").path("POSTGRES_DB").asText()).isEqualTo("mydb");
        }

        @Test @DisplayName("YAML->JSON: GitHub Actions workflow parses correctly")
        void githubActionsYaml() throws Exception {
            String yaml =
                "name: CI\n" +
                "on:\n" +
                "  push:\n" +
                "    branches: [main]\n" +
                "jobs:\n" +
                "  build:\n" +
                "    runs-on: ubuntu-latest\n" +
                "    steps:\n" +
                "      - uses: actions/checkout@v4\n" +
                "      - name: Run tests\n" +
                "        run: ./gradlew test\n";
            JsonNode result = json.readTree(converter.yamlToJson(yaml));
            assertThat(result.get("name").asText()).isEqualTo("CI");
            assertThat(result.path("jobs").path("build").path("runs-on").asText()).isEqualTo("ubuntu-latest");
        }

        // ── Numeric edge cases ────────────────────────────────────────────────

        @Test @DisplayName("JSON->YAML->JSON: float precision maintained")
        void floatPrecision() throws Exception {
            String original = "{\"pi\":3.141592653589793,\"e\":2.718281828}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("pi").asDouble()).isCloseTo(3.141592653589793, within(0.0000001));
        }

        @Test @DisplayName("JSON->YAML->JSON: negative numbers round-trip")
        void negativeNumbers() throws Exception {
            String original = "{\"temp\":-273,\"delta\":-0.001}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("temp").asInt()).isEqualTo(-273);
        }

        @Test @DisplayName("JSON->YAML->JSON: zero values round-trip")
        void zeroValues() throws Exception {
            String original = "{\"count\":0,\"rate\":0.0}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("count").asInt()).isEqualTo(0);
        }

        @Test @DisplayName("JSON->YAML->JSON: 10-field flat object full round-trip")
        void tenFieldFlatObject() throws Exception {
            String original = "{\"f1\":\"v1\",\"f2\":2,\"f3\":3.0,\"f4\":true,\"f5\":null," +
                "\"f6\":\"v6\",\"f7\":7,\"f8\":8.8,\"f9\":false,\"f10\":\"v10\"}";
            JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
            assertThat(back.get("f1").asText()).isEqualTo("v1");
            assertThat(back.get("f7").asInt()).isEqualTo(7);
            assertThat(back.get("f10").asText()).isEqualTo("v10");
        }

        // ── Anchors and aliases ───────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: anchor and alias resolves to same value")
        void yamlAnchorAlias() throws Exception {
            String yaml = "defaults: &defaults\n  timeout: 30\n  retries: 3\nproduction:\n  <<: *defaults\n  host: prod.example.com\n";
            assertThatCode(() -> {
                String result = converter.yamlToJson(yaml);
                assertThat(result).isNotBlank();
            }).doesNotThrowAnyException();
        }

        @Test @DisplayName("YAML->JSON: simple scalar anchor and alias does not throw")
        void yamlSimpleAnchorAlias() throws Exception {
            String yaml = "name: &n Alice\nowner: *n\n";
            assertThatCode(() -> converter.yamlToJson(yaml)).doesNotThrowAnyException();
        }

    // ── Multi-document YAML ───────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: multi-document YAML becomes a JSON array, one element per doc")
        void yamlMultiDocument() throws Exception {
            String yaml = "name: Alice\n---\nname: Bob\n";
            JsonNode result = json.readTree(converter.yamlToJson(yaml));
            assertThat(result.isArray()).isTrue();
            assertThat(result).hasSize(2);
            assertThat(result.get(0).get("name").asText()).isEqualTo("Alice");
            assertThat(result.get(1).get("name").asText()).isEqualTo("Bob");
        }

        @Test @DisplayName("YAML->JSON: single document is NOT wrapped in an array")
        void yamlSingleDocumentNotWrapped() throws Exception {
            JsonNode result = json.readTree(converter.yamlToJson("name: Alice\n"));
            assertThat(result.isObject()).isTrue();
            assertThat(result.get("name").asText()).isEqualTo("Alice");
        }

        @Test @DisplayName("YAML->JSON: trailing document separator does not add a null element")
        void yamlTrailingSeparator() throws Exception {
            JsonNode result = json.readTree(converter.yamlToJson("name: Alice\n---\n"));
            assertThat(result.isObject()).isTrue();
        }

        // ── Security posture (pinned so a SnakeYAML upgrade cannot regress it) ──

        @Test @DisplayName("YAML->JSON: alias-heavy input does not expand exponentially")
        void aliasBombDoesNotExplode() {
            // 9 levels, each referencing the previous 9 times — naive expansion
            // would be 9^9 elements. Must either be rejected or complete quickly
            // with bounded output.
            StringBuilder bomb = new StringBuilder("a: &a [\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\"]\n");
            char prev = 'a';
            for (char c = 'b'; c <= 'j'; c++) {
                bomb.append(c).append(": &").append(c).append(" [");
                for (int i = 0; i < 9; i++) bomb.append(i > 0 ? "," : "").append("*").append(prev);
                bomb.append("]\n");
                prev = c;
            }
            long start = System.currentTimeMillis();
            try {
                String result = converter.yamlToJson(bomb.toString());
                assertThat(result.length()).isLessThan(1_000_000);
            } catch (Exception rejected) {
                // Outright rejection is also an acceptable outcome.
            }
            assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
        }

    // ── Tab indentation ───────────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: tab-indented YAML throws a descriptive error (tabs not allowed in YAML)")
        void yamlTabIndentation() {
            String yaml = "parent:\n\tchild: value\n";
            assertThatThrownBy(() -> converter.yamlToJson(yaml))
                  .isInstanceOf(Exception.class);
        }

    // ── Flow-style collections ────────────────────────────────────────────

        @Test @DisplayName("YAML->JSON: flow-style mapping {a: 1, b: 2} parses correctly")
        void yamlFlowStyleMapping() throws Exception {
            String yaml = "point: {x: 10, y: 20}\n";
            JsonNode result = json.readTree(converter.yamlToJson(yaml));
            assertThat(result.path("point").path("x").asInt()).isEqualTo(10);
            assertThat(result.path("point").path("y").asInt()).isEqualTo(20);
        }

        @Test @DisplayName("YAML->JSON: flow-style sequence [1, 2, 3] parses correctly")
        void yamlFlowStyleSequence() throws Exception {
            String yaml = "scores: [10, 20, 30]\n";
            JsonNode result = json.readTree(converter.yamlToJson(yaml));
            assertThat(result.get("scores").isArray()).isTrue();
            assertThat(result.get("scores").get(0).asInt()).isEqualTo(10);
        }

    // ── Null / blank input ────────────────────────────────────────────────

        @Test @DisplayName("JSON->YAML: null input throws or returns descriptive error")
        void jsonToYamlNullInput() {
            assertThatThrownBy(() -> converter.jsonToYaml(null))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("JSON->YAML: blank input throws or returns descriptive error")
        void jsonToYamlBlankInput() {
            assertThatThrownBy(() -> converter.jsonToYaml("   "))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("YAML->JSON: null input throws or returns descriptive error")
        void yamlToJsonNullInput() {
            assertThatThrownBy(() -> converter.yamlToJson(null))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("YAML->JSON: blank input throws or returns descriptive error")
        void yamlToJsonBlankInput() {
            assertThatThrownBy(() -> converter.yamlToJson("   "))
                  .isInstanceOf(Exception.class);
        }
    }

    /** YAML conversions must not quietly change a value's type or drop one. */
    @Nested @DisplayName("YAML fidelity")
    class Fidelity {
        private final JsonYamlConverter converter = new JsonYamlConverter();

        @Test @DisplayName("a plainly numeric string stays a string across the round trip")
        void numericStringsKeepTheirType() throws Exception {
            String json = "{\"id\":\"123\",\"price\":\"9.99\",\"neg\":\"-5\"}";
            assertThat(converter.jsonToYaml(json)).contains("\"123\"").contains("\"9.99\"");
            assertThat(converter.yamlToJson(converter.jsonToYaml(json)))
                  .contains("\"id\":\"123\"")
                  .contains("\"price\":\"9.99\"")
                  .contains("\"neg\":\"-5\"");
            // Ordinary text is still emitted bare — that is the point of the setting.
            assertThat(converter.jsonToYaml("{\"name\":\"Alice\"}")).contains("name: Alice");
        }

        @Test @DisplayName("times, exponents and dates now survive the round trip too")
        void formerLookalikesNowSurvive() throws Exception {
            // These were sexagesimal, float and timestamp under YAML 1.1.
            assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"12:30:00\"}")))
                  .isEqualTo("{\"a\":\"12:30:00\"}");
            assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"2024-01-01\"}")))
                  .isEqualTo("{\"a\":\"2024-01-01\"}");
            assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"0777\"}")))
                  .isEqualTo("{\"a\":\"0777\"}");
        }

        @Test @DisplayName("hex-, exponent- and underscore-looking strings are quoted, so they stay strings")
        void numberLookalikesAreQuoted() throws Exception {
            // All of these are genuine numbers under the reader's rules, so READING
            // them bare as numbers is right. They used to be EMITTED bare as well:
            // ALWAYS_QUOTE_NUMBERS_AS_STRINGS knows plain decimals only, so "0x1F"
            // came back as 31 and "1e3" as 1000.0. The writer now quotes by the
            // reader's own patterns, so exactly these get quotes and ordinary text
            // stays bare.
            for (String s : new String[]{"0x1F", "1e3", "1_000", "0b11", "1E3", "+1", ".5", "1_"}) {
                String json = "{\"a\":\"" + s + "\"}";
                assertThat(converter.yamlToJson(converter.jsonToYaml(json))).describedAs(s).isEqualTo(json);
            }
            assertThat(converter.jsonToYaml("{\"a\":\"0x1F\"}")).contains("\"0x1F\"");
            assertThat(converter.jsonToYaml("{\"name\":\"Alice\",\"v\":\"1.5.2\"}"))
                  .contains("name: Alice").contains("v: 1.5.2");
        }

        @Test @DisplayName("floats keep the digits they were written with")
        void floatsAreReadExactly() throws Exception {
            // Through double, 1.10 became 1.1, 1e400 the STRING "Infinity", and a
            // long decimal was cut to 17 digits — and Format wrote each back.
            assertThat(converter.yamlToJson("price: 1.10\n")).isEqualTo("{\"price\":1.10}");
            assertThat(converter.yamlToJson("total: 100.00\n")).isEqualTo("{\"total\":100.00}");
            assertThat(converter.yamlToJson("big: 1e400\n")).isEqualTo("{\"big\":1E+400}");
            assertThat(converter.yamlToJson("v: 0.1234567890123456789012345\n"))
                  .isEqualTo("{\"v\":0.1234567890123456789012345}");
            assertThat(converter.yamlToJson("n: -1_000.5\n")).isEqualTo("{\"n\":-1000.5}");
            // The round trip is exact in both directions.
            assertThat(converter.yamlToJson(converter.jsonToYaml("{\"price\":1.10}")))
                  .isEqualTo("{\"price\":1.10}");
        }

        @Test @DisplayName("a lone dot is the string it looks like, not a crash")
        void loneDotIsText() throws Exception {
            // The float pattern accepted "." with no digits at all, tagged it FLOAT,
            // and construction then threw NumberFormatException at a plain value.
            assertThat(converter.yamlToJson("a: .\n")).isEqualTo("{\"a\":\".\"}");
            assertThat(converter.yamlToJson("a: -.\n")).isEqualTo("{\"a\":\"-.\"}");
            assertThat(converter.yamlToJson("a: ._\n")).isEqualTo("{\"a\":\"._\"}");
            // Digits on either side still make a number.
            assertThat(converter.yamlToJson("a: 1.\n")).isEqualTo("{\"a\":1}");
            assertThat(converter.yamlToJson("a: .5\n")).isEqualTo("{\"a\":0.5}");
        }

        @Test @DisplayName("the resolver is exercised directly, not only through quoted output")
        void resolverReadsScalarsDirectly() throws Exception {
            // Round-tripping a JSON string emits it QUOTED, and a quoted scalar
            // never reaches implicit resolution — so those assertions passed whether
            // or not the resolver was right. These read the YAML as written.
            assertThat(converter.yamlToJson("a: 0777\n")).isEqualTo("{\"a\":\"0777\"}");
            assertThat(converter.yamlToJson("a: 010\n")).isEqualTo("{\"a\":\"010\"}");
            assertThat(converter.yamlToJson("a: 12:30:00\n")).isEqualTo("{\"a\":\"12:30:00\"}");
            assertThat(converter.yamlToJson("a: 2024-01-01\n")).isEqualTo("{\"a\":\"2024-01-01\"}");
            // 0o777 is YAML 1.2 octal that SnakeYAML's constructor cannot build, so
            // it stays text rather than throwing NumberFormatException.
            assertThat(converter.yamlToJson("a: 0o777\n")).isEqualTo("{\"a\":\"0o777\"}");
            // Still numbers, per YAML 1.2 — carried as the exact decimals they were
            // written as, so exponent forms print the way BigDecimal prints them.
            assertThat(converter.yamlToJson("a: 1e3\n")).isEqualTo("{\"a\":1E+3}");
            assertThat(converter.yamlToJson("a: .5e3\n")).isEqualTo("{\"a\":5E+2}");
            assertThat(converter.yamlToJson("a: 0x1F\n")).isEqualTo("{\"a\":31}");
            assertThat(converter.yamlToJson("a: 0\n")).isEqualTo("{\"a\":0}");
            assertThat(converter.yamlToJson("a: 42\n")).isEqualTo("{\"a\":42}");
        }

        @Test @DisplayName("a mapping inside a sequence is checked for colliding keys too")
        void collidingKeysInsideSequences() throws Exception {
            // The guard only descended through Map values, so a list of mappings —
            // every Kubernetes containers: block — was never examined.
            assertThatThrownBy(() -> converter.yamlToJson("items:\n  - 1: a\n    \"1\": b\n"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("identical as JSON keys");
            // Nested a second level down, to prove the descent is not one-deep.
            assertThatThrownBy(() -> converter.yamlToJson(
                  "outer:\n  - inner:\n      - 1: a\n        \"1\": b\n"))
                  .isInstanceOf(IllegalArgumentException.class);
            // An ordinary list of mappings still converts.
            assertThat(converter.yamlToJson("items:\n  - a: 1\n  - b: 2\n"))
                  .isEqualTo("{\"items\":[{\"a\":1},{\"b\":2}]}");
        }

        @Test @DisplayName("a document written as null is kept; a bare trailing separator is not")
        void explicitNullDocumentIsKept() throws Exception {
            // Popping every trailing null took the user's own null document with it.
            assertThat(converter.yamlToJson("--- {a: 1}\n--- null\n"))
                  .isEqualTo("[{\"a\":1},null]");
            assertThat(converter.yamlToJson("--- {a: 1}\n--- null\n--- null\n"))
                  .isEqualTo("[{\"a\":1},null,null]");
            // A separator with nothing after it still closes the last document.
            assertThat(converter.yamlToJson("name: Alice\n---\n")).isEqualTo("{\"name\":\"Alice\"}");
            assertThat(converter.yamlToJson("--- {a: 1}\n---\n")).isEqualTo("{\"a\":1}");
        }

        @Test @DisplayName("YAML 1.2 booleans resolve; the 1.1 yes/no/on/off words are text")
        void booleansAreYaml12() throws Exception {
            assertThat(converter.yamlToJson("a: true\nb: False\nc: yes\nd: no\ne: on\nf: off\n"))
                  .isEqualTo("{\"a\":true,\"b\":false,\"c\":\"yes\",\"d\":\"no\",\"e\":\"on\",\"f\":\"off\"}");
            // The GitHub Actions trigger key, which the 1.1 rule turned into "true".
            assertThat(converter.yamlToJson("on:\n  push:\n    branches: [main]\n"))
                  .isEqualTo("{\"on\":{\"push\":{\"branches\":[\"main\"]}}}");
            assertThat(converter.yamlToJson("a: null\nb: ~\nc: 42\nd: 1.5\ne: 0x1F\n"))
                  .isEqualTo("{\"a\":null,\"b\":null,\"c\":42,\"d\":1.5,\"e\":31}");
            // Anchors and merge keys still work — they are why this uses the composer.
            assertThat(converter.yamlToJson("base: &b {x: 1}\nuse:\n  <<: *b\n  y: 2\n"))
                  .contains("\"x\":1").contains("\"y\":2");
        }

        @Test @DisplayName("an anchor that contains its own alias is refused, not a stack overflow")
        void cyclesAreRefused() {
            assertThatThrownBy(() -> converter.yamlToJson("a: &a\n  b: *a\n"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("refers to itself");
            assertThatThrownBy(() -> converter.yamlToJson("a: &a [1, *a]\n"))
                  .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("aliases that would expand past the limit are refused before any copy is made")
        void runawayExpansionIsRefused() throws Exception {
            // Three aliases per level over thirteen levels is 39 aliases — under
            // SnakeYAML's own limit of 50 — and 3^13 copies of the innermost list.
            StringBuilder bomb = new StringBuilder("a0: &a0 [x, x, x]\n");
            for (int level = 1; level <= 13; level++)
                bomb.append("a").append(level).append(": &a").append(level)
                      .append(" [*a").append(level - 1).append(", *a").append(level - 1)
                      .append(", *a").append(level - 1).append("]\n");
            assertThatThrownBy(() -> converter.yamlToJson(bomb.toString()))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("expands to over");
            // Ordinary reuse of an anchor is nowhere near the limit.
            assertThat(converter.yamlToJson("base: &b {x: 1, y: 2}\np: *b\nq: *b\nr: *b\n"))
                  .contains("\"r\":{\"x\":1,\"y\":2}");
        }

        @Test @DisplayName("keys that differ in YAML but collide as JSON are refused")
        void collidingKeysAreRefused() {
            // valueToTree stringifies the key, so the second silently won.
            assertThatThrownBy(() -> converter.yamlToJson("1: a\n\"1\": b\n"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("identical as JSON keys");
            assertThatThrownBy(() -> converter.yamlToJson("true: a\n\"true\": b\n"))
                  .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> converter.yamlToJson("outer:\n  1: a\n  \"1\": b\n"))
                  .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("distinct non-string keys still convert")
        void nonStringKeysStillWork() throws Exception {
            assertThat(converter.yamlToJson("1: a\n2: b\n")).isEqualTo("{\"1\":\"a\",\"2\":\"b\"}");
        }

        @Test @DisplayName("a null key becomes the key \"null\", like every other non-string key")
        void nullKeyBecomesText() throws Exception {
            // Jackson refused it with its own sentence about a NullKeySerializer.
            assertThat(converter.yamlToJson("~: 1\nb: 2\n")).isEqualTo("{\"null\":1,\"b\":2}");
            assertThat(converter.yamlToJson("null: 1\n")).isEqualTo("{\"null\":1}");
            assertThat(converter.yamlToJson("a:\n  ~: x\n")).isEqualTo("{\"a\":{\"null\":\"x\"}}");
            // Beside a literal "null" string key it is a collision, and is refused.
            assertThatThrownBy(() -> converter.yamlToJson("~: 1\n\"null\": 2\n"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("identical as JSON keys");
        }

        @Test @DisplayName("a duplicate mapping key is an error, not a silent last-wins")
        void duplicateKeysAreRefused() {
            assertThatThrownBy(() -> converter.yamlToJson("a: 1\na: 2\n"))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("an interior empty document keeps its place in the stream")
        void interiorEmptyDocumentsArePreserved() throws Exception {
            // Dropping them renumbered every later document.
            assertThat(converter.yamlToJson("--- null\n--- {a: 1}\n")).isEqualTo("[null,{\"a\":1}]");
            assertThat(converter.yamlToJson("--- {a: 1}\n---\n--- {b: 2}\n"))
                  .isEqualTo("[{\"a\":1},null,{\"b\":2}]");
            // A trailing separator terminates the last document rather than starting one.
            assertThat(converter.yamlToJson("name: Alice\n---\n")).isEqualTo("{\"name\":\"Alice\"}");
            assertThatThrownBy(() -> converter.yamlToJson("---\n---\n"))
                  .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested @DisplayName("null and empty documents")
    class NullDocuments {
        private final JsonYamlConverter converter = new JsonYamlConverter();

        @ParameterizedTest
        @ValueSource(strings = {"null", "~", "Null", "NULL", "--- null\n", "null # explicit\n",
              "!!null ''", "--- &empty null\n"})
        void preservesExplicitNullScalars(String yaml) throws Exception {
            assertThat(converter.yamlToJson(yaml)).isEqualTo("null");
            assertThat(converter.yamlToJson(converter.formatPreservingDocuments(yaml, false)))
                  .isEqualTo("null");
        }

        @Test void acceptsTheWritersOwnNullOutput() throws Exception {
            assertThat(converter.yamlToJson(converter.jsonToYaml("null"))).isEqualTo("null");
        }

        @Test void preservesMultipleExplicitNullDocuments() throws Exception {
            String yaml = "--- null\n--- ~\n--- !!null ''\n";
            assertThat(converter.yamlToJson(yaml)).isEqualTo("[null,null,null]");
            assertThat(converter.yamlToJson(converter.formatPreservingDocuments(yaml, true)))
                  .isEqualTo("[null,null,null]");
        }

        @ParameterizedTest
        @ValueSource(strings = {"# comment only\n", "---\n", "---\n...\n", "---\n---\n"})
        void stillRejectsStreamsContainingOnlyEmptyDocuments(String yaml) {
            assertThatThrownBy(() -> converter.yamlToJson(yaml))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no documents");
        }
    }
}
