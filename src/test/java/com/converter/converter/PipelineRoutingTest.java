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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link ConversionPipeline}'s own routing. The converters were well
 * covered but the switch that dispatches to them was not: several arms had no
 * test at all, and the Java arm passes two adjacent booleans that could be
 * transposed without a single failure.
 */
@DisplayName("Pipeline routing")
class PipelineRoutingTest {

    private ConversionPipeline pipeline;

    private static final String INPUT = "{\"id\":1,\"born\":\"2024-01-31\"}";

    @BeforeEach void setUp() { pipeline = new ConversionPipeline(); }

    @ParameterizedTest(name = "renderFromJson -> {0}")
    @CsvSource({
          "JSON,        '\"id\"'",
          "XML,         '<id>'",
          "YAML,        'id:'",
          "CSV,         'id'",
          "TOML,        'id ='",
          "Protobuf,    'message Root'",
          "Java POJO,   'public class Root'",
          "JSON Schema, '$schema'",
    })
    @DisplayName("every output format is reachable and produces its own syntax")
    void everyOutputFormatRoutes(String format, String marker) throws Exception {
        assertThat(pipeline.renderFromJson(INPUT, format, ConversionOptions.DEFAULTS))
              .contains(marker);
    }

    @Test @DisplayName("an unknown output format is rejected, not silently passed through")
    void unknownOutputRejected() {
        assertThatThrownBy(() -> pipeline.renderFromJson(INPUT, "Nonsense",
              ConversionOptions.DEFAULTS)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test @DisplayName("an unknown input format is rejected")
    void unknownInputRejected() {
        assertThatThrownBy(() -> pipeline.normalizeToJson(INPUT, "Nonsense",
              ConversionOptions.DEFAULTS)).isInstanceOf(UnsupportedOperationException.class);
    }

    // ── The two same-typed booleans on the Java arm ───────────────────────

    @Test @DisplayName("useLombok reaches the generator")
    void lombokFlagIsWired() throws Exception {
        assertThat(pipeline.renderFromJson(INPUT, ConversionPipeline.FMT_JAVA,
              ConversionOptions.DEFAULTS.withLombok(true))).contains("@Data");
        assertThat(pipeline.renderFromJson(INPUT, ConversionPipeline.FMT_JAVA,
              ConversionOptions.DEFAULTS.withLombok(false))).doesNotContain("@Data");
    }

    @Test @DisplayName("detectDates reaches the generator")
    void detectDatesFlagIsWired() throws Exception {
        // Transposing useLombok and detectDates compiles and changes the output;
        // asserting both separately is what makes that mistake fail a test.
        assertThat(pipeline.renderFromJson(INPUT, ConversionPipeline.FMT_JAVA,
              ConversionOptions.DEFAULTS.withDetectDates(true))).contains("LocalDate born");
        assertThat(pipeline.renderFromJson(INPUT, ConversionPipeline.FMT_JAVA,
              ConversionOptions.DEFAULTS.withDetectDates(false))).contains("String born");
    }

    // ── formatInput arms ──────────────────────────────────────────────────

    @ParameterizedTest(name = "formatInput({0}) round-trips")
    @CsvSource(delimiter = '|', value = {
          "JSON     | {\"a\":1}                  | a",
          "XML      | <r><a>1</a></r>            | <a>",
          "YAML     | a: 1                       | a:",
          "TOML     | a = 1                      | a =",
          "CSV      | a,b\\n1,2                  | a,b",
          "Protobuf | message M { string s = 1; }| message M",
    })
    @DisplayName("each formatInput arm produces output still readable as that format")
    void formatInputArms(String format, String input, String marker) throws Exception {
        String text = input.replace("\\n", "\n");
        String formatted = pipeline.formatInput(text, format, ConversionOptions.DEFAULTS);
        assertThat(formatted).contains(marker);
        // The real check: the result must still parse as the same format.
        assertThat(pipeline.normalizeToJson(formatted, format, ConversionOptions.DEFAULTS))
              .isNotBlank();
    }

    @Test @DisplayName("Format+Sort keys sorts JSON")
    void formatSortsJson() throws Exception {
        String out = pipeline.formatInput("{\"b\":1,\"a\":2}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS.withSortKeys(true));
        assertThat(out.indexOf("\"a\"")).isLessThan(out.indexOf("\"b\""));
    }

    @Test @DisplayName("Format+Sort keys deliberately leaves YAML alone rather than failing")
    void formatDoesNotSortYaml() throws Exception {
        // sortKeys is a JSON-tree operation; applying it to already-formatted
        // YAML would throw, so the gate is load-bearing rather than an oversight.
        String out = pipeline.formatInput("b: 1\na: 2\n", ConversionPipeline.FMT_YAML,
              ConversionOptions.DEFAULTS.withSortKeys(true));
        assertThat(out.indexOf("b:")).isLessThan(out.indexOf("a:"));
    }

    @Test @DisplayName("formatInput threads the CSV delimiter through both directions")
    void formatInputUsesDelimiter() throws Exception {
        String out = pipeline.formatInput("a;b\n1;2\n", ConversionPipeline.FMT_CSV,
              ConversionOptions.DEFAULTS.withCsvFormat(CsvConverter.CsvFormat.SEMICOLON));
        assertThat(out).contains("a;b").doesNotContain("a;b,");
    }
}
