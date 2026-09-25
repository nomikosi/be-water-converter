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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.converter.core.FormatDetector.detectFormat;
import static org.assertj.core.api.Assertions.assertThat;

/** Telling a document's format, and a CSV file's delimiter, from its content alone. */
@DisplayName("Format detection")
class FormatDetectorTest {

    @Test @DisplayName("JSON objects and arrays")
    void json() {
        assertThat(detectFormat("{\"a\":1}")).isEqualTo(Formats.FMT_JSON);
        assertThat(detectFormat("  [1,2,3]  ")).isEqualTo(Formats.FMT_JSON);
    }

    @Test @DisplayName("XML by leading angle bracket, with or without a declaration")
    void xml() {
        assertThat(detectFormat("<root><a>1</a></root>")).isEqualTo(Formats.FMT_XML);
        assertThat(detectFormat("<?xml version=\"1.0\"?><r/>")).isEqualTo(Formats.FMT_XML);
    }

    @Test @DisplayName("YAML document marker and plain mappings")
    void yaml() {
        assertThat(detectFormat("---\na: 1\n")).isEqualTo(Formats.FMT_YAML);
        assertThat(detectFormat("name: Ada\nage: 36\n")).isEqualTo(Formats.FMT_YAML);
        assertThat(detectFormat("- one\n- two\n")).isEqualTo(Formats.FMT_YAML);
    }

    @Test @DisplayName("TOML tables and key = value win over YAML")
    void toml() {
        assertThat(detectFormat("[server]\nhost = \"localhost\"\n"))
              .isEqualTo(Formats.FMT_TOML);
        assertThat(detectFormat("[[products]]\nname = \"hammer\"\n"))
              .isEqualTo(Formats.FMT_TOML);
        // 'key = value' is TOML; the YAML pattern requires a colon, so no clash.
        assertThat(detectFormat("title = \"demo\"\n")).isEqualTo(Formats.FMT_TOML);
    }

    @Test @DisplayName("'[' stays JSON unless a TOML key = value line follows")
    void bracketAmbiguity() {
        // A TOML [table] header and a JSON array open identically; only the
        // following lines disambiguate.
        assertThat(detectFormat("[1,2,3]")).isEqualTo(Formats.FMT_JSON);
        assertThat(detectFormat("[\n  {\"a\": 1}\n]")).isEqualTo(Formats.FMT_JSON);
        assertThat(detectFormat("[\"only\"]")).isEqualTo(Formats.FMT_JSON);
        assertThat(detectFormat("[owner]\nname = \"Ada\"")).isEqualTo(Formats.FMT_TOML);
    }

    @Test @DisplayName("Protobuf by syntax/message/enum keyword")
    void proto() {
        assertThat(detectFormat("syntax = \"proto3\";\nmessage M { string s = 1; }"))
              .isEqualTo(Formats.FMT_PROTO);
        assertThat(detectFormat("message Person {\n  string name = 1;\n}"))
              .isEqualTo(Formats.FMT_PROTO);
    }

    @Test @DisplayName("CSV needs a delimiter and a consistent second row")
    void csv() {
        assertThat(detectFormat("a,b,c\n1,2,3\n")).isEqualTo(Formats.FMT_CSV);
        // A single line is not enough evidence — prose with a comma is not CSV.
        assertThat(detectFormat("Hello, world")).isNull();
        // Inconsistent column counts: not CSV.
        assertThat(detectFormat("a,b,c\n1,2\n")).isNull();
    }

    @Test @DisplayName("Unrecognisable input returns null rather than guessing")
    void unknown() {
        assertThat(detectFormat("just some prose")).isNull();
        assertThat(detectFormat("")).isNull();
        assertThat(detectFormat("   ")).isNull();
        assertThat(detectFormat(null)).isNull();
    }

    @Test @DisplayName("Detected format actually round-trips through the pipeline")
    void detectedFormatIsUsable() throws Exception {
        ConversionPipeline pipeline = new ConversionPipeline();
        String[] samples = {
              "{\"a\":1}",
              "<root><a>1</a></root>",
              "name: Ada\n",
              "title = \"demo\"\n",
              "[server]\nhost = \"localhost\"\n",
              "[\n  {\"a\": 1}\n]",
              "syntax = \"proto3\";\nmessage M { string s = 1; }",
              "a,b\n1,2\n",
        };
        for (String sample : samples) {
            String detected = detectFormat(sample);
            assertThat(detected).as("detected for: " + sample).isNotNull();
            // The real assertion: whatever we detected must parse as that format.
            assertThat(pipeline.normalizeToJson(sample, detected, ConversionOptions.DEFAULTS.withInferTypes(true)))
                  .as("pivot for: " + sample).isNotBlank();
        }
    }

    @Test @DisplayName("detection stays linear over long runs of blank lines")
    void detectionIsNotQuadraticOverBlankLines() {
        // "^\s*" in the markers ran over every blank line after each line start
        // and backtracked through the run one character at a time: 16,000 blank
        // lines took seven seconds, on the EDT, for a paste.
        String text = "x\n" + "\n".repeat(16_000) + "a: 1\n";
        long started = System.nanoTime();
        assertThat(FormatDetector.detectFormat(text)).isEqualTo(Formats.FMT_YAML);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis).describedAs("detection took %d ms", millis).isLessThan(2_000);
        // The markers still see an indented or a bare line for what it is.
        assertThat(FormatDetector.detectFormat("x\n\n\n  key = 1\n"))
              .isEqualTo(Formats.FMT_TOML);
        assertThat(FormatDetector.detectFormat("x\n-\n  a: 1\n"))
              .isEqualTo(Formats.FMT_YAML);
    }

    @Nested @DisplayName("real documents whose later lines look like another format")
    class RealDocuments {

        private final ConversionPipeline pipeline = new ConversionPipeline();

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
            assertThat(detectFormat(workflow)).isEqualTo(Formats.FMT_YAML);
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
            assertThat(detectFormat(manifest)).isEqualTo(Formats.FMT_YAML);
        }

        @Test @DisplayName("a leading comment block does not decide the format")
        void leadingCommentsSkipped() {
            assertThat(detectFormat("# a comment\n# another\nname: Ada\n"))
                  .isEqualTo(Formats.FMT_YAML);
            assertThat(detectFormat("# a comment\ntitle = \"demo\"\n"))
                  .isEqualTo(Formats.FMT_TOML);
        }

        @Test @DisplayName("real TOML is still TOML")
        void tomlStillDetected() {
            assertThat(detectFormat("""
                  [package]
                  name = "demo"
                  version = "0.1.0"

                  [dependencies]
                  serde = "1"
                  """)).isEqualTo(Formats.FMT_TOML);
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

        @Test @DisplayName("a source file embedded in YAML is not detected as Protobuf")
        void embeddedPackageLineIsNotProto() {
            // "package x;" is ordinary Java, Kotlin and Go, and the marker was
            // searched for across the whole document.
            String configMap = "apiVersion: v1\nkind: ConfigMap\ndata:\n  Main.java: |\n"
                  + "    package com.example;\n    class Main {}\n";
            assertThat(FormatDetector.detectFormat(configMap)).isEqualTo(Formats.FMT_YAML);
        }

        @Test @DisplayName("real Protobuf is still detected")
        void realProtoStillDetected() {
            assertThat(FormatDetector.detectFormat("syntax = \"proto3\";\nmessage P { string a = 1; }"))
                  .isEqualTo(Formats.FMT_PROTO);
            assertThat(FormatDetector.detectFormat("message Person {\n  string name = 1;\n}"))
                  .isEqualTo(Formats.FMT_PROTO);
            assertThat(FormatDetector.detectFormat("enum Colour {\n  RED = 0;\n}"))
                  .isEqualTo(Formats.FMT_PROTO);
        }
    }

    @Nested @DisplayName("leading comments and CSV delimiters")
    class CommentsAndDelimiters {

        @Test @DisplayName("detection looks past leading comment lines")
        void detectionSkipsLeadingComments() {
            assertThat(FormatDetector.detectFormat("// note\n{\"a\": 1}")).isEqualTo(Formats.FMT_JSON);
            assertThat(FormatDetector.detectFormat("# note\n{\"a\": 1}")).isEqualTo(Formats.FMT_JSON);
            assertThat(FormatDetector.detectFormat("/* note\n   more */\n{\"a\": 1}")).isEqualTo(Formats.FMT_JSON);
            // A bracket line after a comment was the TOML table-header check's
            // first line, so a JSON array with a comment above it was TOML.
            assertThat(FormatDetector.detectFormat("# note\n[1, 2]")).isEqualTo(Formats.FMT_JSON);
            assertThat(FormatDetector.detectFormat("# note\n[server]\nport = 1")).isEqualTo(Formats.FMT_TOML);
            assertThat(FormatDetector.detectFormat("# note\n- a\n- b")).isEqualTo(Formats.FMT_YAML);
            assertThat(FormatDetector.detectFormat("// licence\nsyntax = \"proto3\";\nmessage A { int32 x = 1; }"))
                  .isEqualTo(Formats.FMT_PROTO);
            // Nothing but comments is nothing.
            assertThat(FormatDetector.detectFormat("# note\n// note")).isNull();
            assertThat(FormatDetector.detectFormat("/* never closed")).isNull();
            // A comment inside the document does not start it over.
            assertThat(FormatDetector.withoutLeadingComments("a: 1\n# c\nb: 2")).isEqualTo("a: 1\n# c\nb: 2");
        }

        @Test @DisplayName("a '#'-prefixed CSV header is a header, not a comment")
        void hashHeadedCsvIsStillCsv() {
            // Skipping leading comments for the other formats took the header off
            // a CSV file that opens with one, and the single line left behind was
            // too little to compare column counts against: a two-line file stopped
            // being detected at all. The convention is ordinary in tab-separated
            // exports, so the CSV check reads the document as written.
            assertThat(FormatDetector.detectFormat("#id,name\n1,Ann")).isEqualTo(Formats.FMT_CSV);
            assertThat(FormatDetector.detectFormat("#id,name\n1,Ann\n2,Bob")).isEqualTo(Formats.FMT_CSV);
            assertThat(FormatDetector.detectFormat("#chrom\tpos\nchr1\t100"))
                  .isEqualTo(Formats.FMT_CSV);
            assertThat(FormatDetector.detectFormat("#id;name\n1;Ann")).isEqualTo(Formats.FMT_CSV);
            // The delimiter comes from the same reading, so the panel selects it
            // rather than leaving the combo on whatever was there before.
            assertThat(FormatDetector.detectCsvDelimiter("#chrom\tpos\nchr1\t100")).isEqualTo('\t');
            assertThat(FormatDetector.detectCsvDelimiter("#id;name\n1;Ann")).isEqualTo(';');
            // The other shape: a genuine note above a real header. Both are CSV,
            // so both readings are tried and the delimiter is found either way.
            assertThat(FormatDetector.detectFormat("# exported\nid,name\n1,Ann"))
                  .isEqualTo(Formats.FMT_CSV);
            assertThat(FormatDetector.detectCsvDelimiter("# exported\nid;name\n1;Ann")).isEqualTo(';');
            // A commented header block over YAML or TOML must not tip either into
            // CSV: every structural check still runs first, on the stripped text.
            assertThat(FormatDetector.detectFormat("# id, name\n# 1, Ann\nkey: value"))
                  .isEqualTo(Formats.FMT_YAML);
            assertThat(FormatDetector.detectFormat("# id, name\n# 1, Ann\n[server]\nport = 1"))
                  .isEqualTo(Formats.FMT_TOML);
            assertThat(FormatDetector.detectFormat("# id, name\n// 1, Ann\n{\"a\": 1}"))
                  .isEqualTo(Formats.FMT_JSON);
        }

        @Test @DisplayName("semicolon- and tab-separated CSV are detected, with their delimiter")
        void csvDelimiterDetection() {
            // Only the comma was tried, so a semicolon file was not CSV at all.
            assertThat(FormatDetector.detectFormat("id;name\n1;Ada\n")).isEqualTo(Formats.FMT_CSV);
            assertThat(FormatDetector.detectCsvDelimiter("id;name\n1;Ada\n")).isEqualTo(';');
            assertThat(FormatDetector.detectFormat("id\tname\n1\tAda\n")).isEqualTo(Formats.FMT_CSV);
            assertThat(FormatDetector.detectCsvDelimiter("id\tname\n1\tAda\n")).isEqualTo('\t');
            assertThat(FormatDetector.detectCsvDelimiter("id,name\n1,Ada\n")).isEqualTo(',');
            // A decimal comma inside a semicolon file does not make it comma-separated.
            assertThat(FormatDetector.detectCsvDelimiter("price;qty\n1,5;2\n2,5;3\n")).isEqualTo(';');
            assertThat(FormatDetector.detectCsvDelimiter("just some prose\nwith no delimiter\n")).isNull();
            assertThat(FormatDetector.detectCsvDelimiter("a: 1\nb: 2\n")).isNull();
        }
    }
}
