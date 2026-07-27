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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.converter.converter.ConversionPipeline.detectFormat;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Content-based format detection")
class FormatDetectionTest {

    @Test @DisplayName("JSON objects and arrays")
    void json() {
        assertThat(detectFormat("{\"a\":1}")).isEqualTo(ConversionPipeline.FMT_JSON);
        assertThat(detectFormat("  [1,2,3]  ")).isEqualTo(ConversionPipeline.FMT_JSON);
    }

    @Test @DisplayName("XML by leading angle bracket, with or without a declaration")
    void xml() {
        assertThat(detectFormat("<root><a>1</a></root>")).isEqualTo(ConversionPipeline.FMT_XML);
        assertThat(detectFormat("<?xml version=\"1.0\"?><r/>")).isEqualTo(ConversionPipeline.FMT_XML);
    }

    @Test @DisplayName("YAML document marker and plain mappings")
    void yaml() {
        assertThat(detectFormat("---\na: 1\n")).isEqualTo(ConversionPipeline.FMT_YAML);
        assertThat(detectFormat("name: Ada\nage: 36\n")).isEqualTo(ConversionPipeline.FMT_YAML);
        assertThat(detectFormat("- one\n- two\n")).isEqualTo(ConversionPipeline.FMT_YAML);
    }

    @Test @DisplayName("TOML tables and key = value win over YAML")
    void toml() {
        assertThat(detectFormat("[server]\nhost = \"localhost\"\n"))
              .isEqualTo(ConversionPipeline.FMT_TOML);
        assertThat(detectFormat("[[products]]\nname = \"hammer\"\n"))
              .isEqualTo(ConversionPipeline.FMT_TOML);
        // 'key = value' is TOML; the YAML pattern requires a colon, so no clash.
        assertThat(detectFormat("title = \"demo\"\n")).isEqualTo(ConversionPipeline.FMT_TOML);
    }

    @Test @DisplayName("'[' stays JSON unless a TOML key = value line follows")
    void bracketAmbiguity() {
        // A TOML [table] header and a JSON array open identically; only the
        // following lines disambiguate.
        assertThat(detectFormat("[1,2,3]")).isEqualTo(ConversionPipeline.FMT_JSON);
        assertThat(detectFormat("[\n  {\"a\": 1}\n]")).isEqualTo(ConversionPipeline.FMT_JSON);
        assertThat(detectFormat("[\"only\"]")).isEqualTo(ConversionPipeline.FMT_JSON);
        assertThat(detectFormat("[owner]\nname = \"Ada\"")).isEqualTo(ConversionPipeline.FMT_TOML);
    }

    @Test @DisplayName("Protobuf by syntax/message/enum keyword")
    void proto() {
        assertThat(detectFormat("syntax = \"proto3\";\nmessage M { string s = 1; }"))
              .isEqualTo(ConversionPipeline.FMT_PROTO);
        assertThat(detectFormat("message Person {\n  string name = 1;\n}"))
              .isEqualTo(ConversionPipeline.FMT_PROTO);
    }

    @Test @DisplayName("CSV needs a delimiter and a consistent second row")
    void csv() {
        assertThat(detectFormat("a,b,c\n1,2,3\n")).isEqualTo(ConversionPipeline.FMT_CSV);
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
            assertThat(pipeline.normalizeToJson(sample, detected, true))
                  .as("pivot for: " + sample).isNotBlank();
        }
    }
}
