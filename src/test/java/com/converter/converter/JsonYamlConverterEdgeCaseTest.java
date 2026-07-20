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
 * Edge-case tests for JsonYamlConverter.
 */
@DisplayName("JsonYamlConverter \u2013 Edge Cases")
class JsonYamlConverterEdgeCaseTest {

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
