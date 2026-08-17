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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Format re-lays-out a document; it must never change what the document says. */
@DisplayName("Format and detection")
class FormatAndDetectionTest {

    private final ConversionPipeline pipeline = new ConversionPipeline();
    private final ConversionOptions opts = ConversionOptions.DEFAULTS;

    @Test @DisplayName("Format keeps a multi-document YAML file multi-document")
    void multiDocumentYamlSurvivesFormat() throws Exception {
        // The stream became a JSON array and came back as one sequence, so two
        // manifests were replaced by a single list.
        String input = "kind: Service\nname: a\n---\nkind: ConfigMap\nname: b\n";
        String out = pipeline.formatInput(input, ConversionPipeline.FMT_YAML, opts);
        assertThat(out).contains("---");
        assertThat(out).contains("kind: Service").contains("kind: ConfigMap");
        // Not a sequence: no document should have been turned into a list item.
        assertThat(out.stripLeading()).doesNotStartWith("-");
        // Round-trips back to two documents rather than one.
        assertThat(pipeline.normalizeToJson(out, ConversionPipeline.FMT_YAML, opts))
              .startsWith("[").contains("Service").contains("ConfigMap");
    }

    @Test @DisplayName("Format leaves a single YAML document unwrapped")
    void singleDocumentYamlUnchanged() throws Exception {
        assertThat(pipeline.formatInput("a: 1\n", ConversionPipeline.FMT_YAML, opts))
              .contains("a: 1").doesNotContain("---");
    }

    @Test @DisplayName("Format refuses to rewrite a TOML date as a string")
    void tomlDatesAreNotRetyped() {
        // TOML has date types and JSON does not, so the pivot stringified them
        // and Format wrote the quoted form back over the user's file.
        assertThatThrownBy(() -> pipeline.formatInput(
              "[meta]\ncreated = 1979-05-27T07:32:00Z\n", ConversionPipeline.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("quoted string");
        assertThatThrownBy(() -> pipeline.formatInput(
              "day = 1979-05-27\n", ConversionPipeline.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("Format still tidies TOML that carries no dates")
    void tomlWithoutDatesStillFormats() throws Exception {
        assertThat(pipeline.formatInput("a=1\nb=\"x\"\n", ConversionPipeline.FMT_TOML, opts))
              .contains("a = 1").contains("b = 'x'");   // the TOML writer quotes with '
        // A date inside a string or a comment is not a date value.
        assertThat(pipeline.formatInput("a = \"1979-05-27\"\n", ConversionPipeline.FMT_TOML, opts))
              .contains("1979-05-27");
    }

    @Test @DisplayName("a source file embedded in YAML is not detected as Protobuf")
    void embeddedPackageLineIsNotProto() {
        // "package x;" is ordinary Java, Kotlin and Go, and the marker was
        // searched for across the whole document.
        String configMap = "apiVersion: v1\nkind: ConfigMap\ndata:\n  Main.java: |\n"
              + "    package com.example;\n    class Main {}\n";
        assertThat(ConversionPipeline.detectFormat(configMap)).isEqualTo(ConversionPipeline.FMT_YAML);
    }

    @Test @DisplayName("real Protobuf is still detected")
    void realProtoStillDetected() {
        assertThat(ConversionPipeline.detectFormat("syntax = \"proto3\";\nmessage P { string a = 1; }"))
              .isEqualTo(ConversionPipeline.FMT_PROTO);
        assertThat(ConversionPipeline.detectFormat("message Person {\n  string name = 1;\n}"))
              .isEqualTo(ConversionPipeline.FMT_PROTO);
        assertThat(ConversionPipeline.detectFormat("enum Colour {\n  RED = 0;\n}"))
              .isEqualTo(ConversionPipeline.FMT_PROTO);
    }

    @Test @DisplayName("autoClose leaves input the lenient reader already accepts alone")
    void autoCloseRespectsCommentsAndSingleQuotes() throws Exception {
        // A brace inside a comment or a single-quoted string was counted as
        // real, and the appended closer made valid input fail to parse.
        assertThat(pipeline.autoClose("{\"a\":1} // trailing {")).isEqualTo("{\"a\":1} // trailing {");
        assertThat(pipeline.autoClose("{'a':'it {'}")).isEqualTo("{'a':'it {'}");
        assertThat(pipeline.autoClose("{\"a\":1} /* note [ */")).isEqualTo("{\"a\":1} /* note [ */");
        assertThat(pipeline.autoClose("{\"a\":1} # hash {")).isEqualTo("{\"a\":1} # hash {");
        // Genuinely unclosed input is still completed.
        assertThat(pipeline.autoClose("{\"a\":[1,2")).isEqualTo("{\"a\":[1,2]}");
        assertThat(pipeline.autoClose("{\"a\":\"unterminated")).isEqualTo("{\"a\":\"unterminated\"}");
        // And the completed forms parse.
        assertThat(pipeline.formatInput("{\"a\":1} // trailing {", ConversionPipeline.FMT_JSON, opts))
              .contains("\"a\"");
    }
}
