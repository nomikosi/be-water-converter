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

import com.converter.converter.CsvConverter.CsvFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.converter.converter.ConversionPipeline.detectFormat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Detection on real documents, and options-aware compare")
class DetectionAndCompareTest {

    private ConversionPipeline pipeline;

    @BeforeEach void setUp() { pipeline = new ConversionPipeline(); }

    // ── Detection must not be fooled by content further down ──────────────

    @Test @DisplayName("a GitHub Actions workflow is YAML, not TOML")
    void githubActionsIsYaml() {
        // A shell assignment inside a run block used to flip the whole file to
        // TOML, because the TOML marker was searched document-wide.
        String workflow = """
              name: CI
              on: push
              jobs:
                build:
                  steps:
                    - run: |
                        VERSION=1.2.3
                        echo $VERSION
              """;
        assertThat(detectFormat(workflow)).isEqualTo(ConversionPipeline.FMT_YAML);
    }

    @Test @DisplayName("a k8s manifest with env KEY=value is YAML")
    void kubernetesIsYaml() {
        String manifest = """
              apiVersion: v1
              kind: Pod
              metadata:
                name: demo
              spec:
                containers:
                  - name: app
                    command: ["sh", "-c", "FOO=bar exec app"]
              """;
        assertThat(detectFormat(manifest)).isEqualTo(ConversionPipeline.FMT_YAML);
    }

    @Test @DisplayName("a leading comment block does not decide the format")
    void leadingCommentsSkipped() {
        assertThat(detectFormat("# a comment\n# another\nname: Ada\n"))
              .isEqualTo(ConversionPipeline.FMT_YAML);
        assertThat(detectFormat("# a comment\ntitle = \"demo\"\n"))
              .isEqualTo(ConversionPipeline.FMT_TOML);
    }

    @Test @DisplayName("real TOML is still TOML")
    void tomlStillDetected() {
        assertThat(detectFormat("""
              [package]
              name = "demo"
              version = "0.1.0"

              [dependencies]
              serde = "1"
              """)).isEqualTo(ConversionPipeline.FMT_TOML);
    }

    @Test @DisplayName("every detected format actually parses as that format")
    void detectionIsUsable() throws Exception {
        String[] samples = {
              "name: CI\non: push\njobs:\n  build:\n    steps:\n      - run: FOO=1\n",
              "[package]\nname = \"demo\"\n",
              "{\"a\":1}",
              "<root><a>1</a></root>",
              "a,b\n1,2\n",
              "syntax = \"proto3\";\nmessage M { string s = 1; }",
        };
        for (String sample : samples) {
            String detected = detectFormat(sample);
            assertThat(detected).as("detected for: " + sample).isNotNull();
            assertThat(pipeline.normalizeToJson(sample, detected, ConversionOptions.DEFAULTS))
                  .as("pivot for: " + sample).isNotBlank();
        }
    }

    // ── Compare honours the user's settings ───────────────────────────────

    @Test @DisplayName("canonicalJson respects a non-comma delimiter")
    void compareHonoursDelimiter() throws Exception {
        ConversionOptions semi = ConversionOptions.DEFAULTS.withCsvFormat(CsvFormat.SEMICOLON);
        String canonical = pipeline.canonicalJson("id;name\n1;Joe\n",
              ConversionPipeline.FMT_CSV, semi);
        // With defaults this produced one column named "id;name".
        assertThat(pipeline.parseJson(canonical).get(0).has("id")).isTrue();
        assertThat(pipeline.parseJson(canonical).get(0).has("name")).isTrue();
    }

    @Test @DisplayName("a CSV converted and compared with the same delimiter is equivalent")
    void roundTripComparesEqual() throws Exception {
        ConversionOptions tab = ConversionOptions.DEFAULTS.withCsvFormat(CsvFormat.TAB);
        String csv = "id\tname\n1\tJoe\n";
        String pivot = pipeline.normalizeToJson(csv, ConversionPipeline.FMT_CSV, tab);
        String rendered = pipeline.renderFromJson(pivot, ConversionPipeline.FMT_CSV, tab);
        assertThat(pipeline.canonicalJson(csv, ConversionPipeline.FMT_CSV, tab))
              .isEqualTo(pipeline.canonicalJson(rendered, ConversionPipeline.FMT_CSV, tab));
    }

    @Test @DisplayName("canonicalJson respects inferTypes")
    void compareHonoursInferTypes() throws Exception {
        String typed = pipeline.canonicalJson("id\n1\n", ConversionPipeline.FMT_CSV,
              ConversionOptions.DEFAULTS);
        String literal = pipeline.canonicalJson("id\n1\n", ConversionPipeline.FMT_CSV,
              ConversionOptions.DEFAULTS.withInferTypes(false));
        assertThat(typed).isNotEqualTo(literal);
    }

    // ── Proto field options ───────────────────────────────────────────────

    @Test @DisplayName("a field option no longer kills the whole message")
    void protoFieldOption() throws Exception {
        String json = pipeline.normalizeToJson(
              "message A { string a = 1 [deprecated = true]; int32 b = 2; }",
              ConversionPipeline.FMT_PROTO, ConversionOptions.DEFAULTS);
        assertThat(json).contains("\"a\"").contains("\"b\"");
    }

    @Test @DisplayName("an option on an enum's zero value keeps it as the default")
    void protoEnumOption() throws Exception {
        String json = pipeline.normalizeToJson("""
              enum Color { RED = 0 [deprecated = true]; GREEN = 1; }
              message M { Color c = 1; }
              """, ConversionPipeline.FMT_PROTO, ConversionOptions.DEFAULTS);
        // Previously RED was skipped and GREEN silently became the default.
        assertThat(json).contains("RED").doesNotContain("GREEN");
    }

    @Test @DisplayName("repeated fields with options parse")
    void protoRepeatedOption() throws Exception {
        assertThat(pipeline.normalizeToJson(
              "message A { repeated int32 xs = 1 [packed = true]; }",
              ConversionPipeline.FMT_PROTO, ConversionOptions.DEFAULTS)).contains("\"xs\"");
    }

    // ── Degenerate outputs report themselves clearly ──────────────────────

    @Test @DisplayName("zero-column CSV explains itself instead of leaking Jackson internals")
    void zeroColumnCsv() {
        assertThatThrownBy(() -> pipeline.renderFromJson("[{}]", ConversionPipeline.FMT_CSV,
              ConversionOptions.DEFAULTS))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("no columns");
    }

    @Test @DisplayName("an empty object renders as TOML that parses back")
    void emptyTomlRoundTrips() throws Exception {
        String toml = pipeline.renderFromJson("{}", ConversionPipeline.FMT_TOML,
              ConversionOptions.DEFAULTS);
        assertThat(toml).doesNotStartWith(" = ");
        // Format on a comment-only TOML file used to yield invalid output.
        assertThat(pipeline.formatInput("# just a comment\n", ConversionPipeline.FMT_TOML,
              ConversionOptions.DEFAULTS)).doesNotStartWith(" = ");
    }

    // ── JSON Schema anyOf de-duplication ──────────────────────────────────

    @Test @DisplayName("objects differing only in key order collapse to one anyOf branch")
    void schemaDedupIgnoresKeyOrder() throws Exception {
        String schema = pipeline.renderFromJson("{\"items\":[{\"a\":1,\"b\":2},{\"b\":3,\"a\":4}]}",
              ConversionPipeline.FMT_SCHEMA, ConversionOptions.DEFAULTS);
        assertThat(pipeline.parseJson(schema).at("/properties/items/items/anyOf").isMissingNode())
              .isTrue();
    }

    @Test @DisplayName("genuinely different shapes still produce anyOf")
    void schemaKeepsRealVariants() throws Exception {
        String schema = pipeline.renderFromJson("{\"items\":[{\"a\":1},{\"z\":\"s\"}]}",
              ConversionPipeline.FMT_SCHEMA, ConversionOptions.DEFAULTS);
        assertThat(pipeline.parseJson(schema).at("/properties/items/items/anyOf")).hasSize(2);
    }
}
