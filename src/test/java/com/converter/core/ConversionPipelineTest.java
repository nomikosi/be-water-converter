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
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The UI-independent pipeline: lenient JSON input, the compact pivot it
 * converts through, and which converter each format pair is routed to.
 */
@DisplayName("Conversion pipeline")
class ConversionPipelineTest {

    private ConversionPipeline pipeline;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        pipeline = new ConversionPipeline();
        json     = new ObjectMapper();
    }

    private final ConversionOptions opts = ConversionOptions.DEFAULTS;

    @Test @DisplayName("JSON input tolerates comments, trailing commas and single quotes")
    void lenientJsonInput() throws Exception {
        String messy = "{\n  // a comment\n  'name': \"Alice\",\n  \"tags\": [1, 2,],\n}";
        String strict = pipeline.normalizeToJson(messy, Formats.FMT_JSON, ConversionOptions.DEFAULTS.withInferTypes(true));
        JsonNode node = json.readTree(strict);
        assertThat(node.get("name").asText()).isEqualTo("Alice");
        assertThat(node.get("tags")).hasSize(2);
    }

    @Test @DisplayName("JSON input tolerates unquoted field names")
    void unquotedFieldNames() throws Exception {
        String strict = pipeline.normalizeToJson("{name: \"Bob\"}", Formats.FMT_JSON, ConversionOptions.DEFAULTS.withInferTypes(true));
        assertThat(json.readTree(strict).get("name").asText()).isEqualTo("Bob");
    }

    @Test @DisplayName("The JSON pivot is compact, not indented")
    void jsonPivotIsCompact() throws Exception {
        // The pivot is re-parsed by the next stage and never displayed, so
        // indenting it only inflates a string nobody reads (~1.5x on large input).
        String compactInput = "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]";
        String pivot = pipeline.normalizeToJson(compactInput, Formats.FMT_JSON, ConversionOptions.DEFAULTS.withInferTypes(false));
        assertThat(pivot).doesNotContain("\n").hasSameSizeAs(compactInput);
        // Still strict, parseable JSON with the same content.
        assertThat(json.readTree(pivot)).isEqualTo(json.readTree(compactInput));
    }

    @Test @DisplayName("JSON output stays pretty-printed for the user")
    void jsonOutputStaysIndented() throws Exception {
        String pretty = pipeline.renderFromJson("[{\"id\":1}]", Formats.FMT_JSON,
              ConversionOptions.DEFAULTS);
        assertThat(pretty).contains("\n");
    }

    @Test @DisplayName("a comment-only JSON document is refused, not converted to null")
    void commentOnlyJsonIsRefused() {
        // The reader hands back a missing node for it, which serialised as the
        // document "null" and reported a successful conversion.
        for (String input : new String[]{"// todo", "# todo", "/* todo */", "  \n// a\n// b\n"}) {
            assertThatThrownBy(() -> pipeline.normalizeToJson(input, Formats.FMT_JSON, opts))
                  .describedAs(input)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("no value");
            assertThatThrownBy(() -> pipeline.formatInput(input, Formats.FMT_JSON, opts))
                  .describedAs(input)
                  .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Pins {@link ConversionPipeline}'s own routing. The converters were well
     * covered but the switch that dispatches to them was not: several arms had no
     * test at all, and the Java arm passes two adjacent booleans that could be
     * transposed without a single failure.
     */
    @Nested @DisplayName("routing")
    class Routing {
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
              "Kotlin,      'data class Root('",
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
            assertThat(pipeline.renderFromJson(INPUT, Formats.FMT_JAVA,
                  ConversionOptions.DEFAULTS.withLombok(true))).contains("@Data");
            assertThat(pipeline.renderFromJson(INPUT, Formats.FMT_JAVA,
                  ConversionOptions.DEFAULTS.withLombok(false))).doesNotContain("@Data");
        }

        @Test @DisplayName("detectDates reaches the generator")
        void detectDatesFlagIsWired() throws Exception {
            // Transposing useLombok and detectDates compiles and changes the output;
            // asserting both separately is what makes that mistake fail a test.
            assertThat(pipeline.renderFromJson(INPUT, Formats.FMT_JAVA,
                  ConversionOptions.DEFAULTS.withDetectDates(true))).contains("LocalDate born");
            assertThat(pipeline.renderFromJson(INPUT, Formats.FMT_JAVA,
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
            String out = pipeline.formatInput("{\"b\":1,\"a\":2}", Formats.FMT_JSON,
                  ConversionOptions.DEFAULTS.withSortKeys(true));
            assertThat(out.indexOf("\"a\"")).isLessThan(out.indexOf("\"b\""));
        }

        @Test @DisplayName("Format+Sort keys now sorts YAML and TOML too")
        void formatSortsTreeBackedFormats() throws Exception {
            // Previously skipped, on the reasoning that sorting already-formatted
            // YAML would throw. It does not: Format already passes through the JSON
            // tree, so the sort happens there, while the tree exists.
            String yaml = pipeline.formatInput("b: 1\na: 2\n", Formats.FMT_YAML,
                  ConversionOptions.DEFAULTS.withSortKeys(true));
            assertThat(yaml.indexOf("a:")).isLessThan(yaml.indexOf("b:"));

            String toml = pipeline.formatInput("b = 1\na = 2\n", Formats.FMT_TOML,
                  ConversionOptions.DEFAULTS.withSortKeys(true));
            assertThat(toml.indexOf("a ")).isLessThan(toml.indexOf("b "));

            // Without the option the document's own order is kept.
            String unsorted = pipeline.formatInput("b: 1\na: 2\n", Formats.FMT_YAML,
                  ConversionOptions.DEFAULTS);
            assertThat(unsorted.indexOf("b:")).isLessThan(unsorted.indexOf("a:"));
        }

        @Test @DisplayName("formatInput threads the CSV delimiter through both directions")
        void formatInputUsesDelimiter() throws Exception {
            String out = pipeline.formatInput("a;b\n1;2\n", Formats.FMT_CSV,
                  ConversionOptions.DEFAULTS.withCsvFormat(CsvConverter.CsvFormat.SEMICOLON));
            assertThat(out).contains("a;b").doesNotContain("a;b,");
        }
    }

    @Nested @DisplayName("Line breaks")
    class LineBreaks {
        private final ConversionPipeline pipeline = new ConversionPipeline();

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"JSON", "XML", "YAML", "CSV", "TOML", "Protobuf", "Java POJO", "Kotlin", "JSON Schema"})
        @DisplayName("every output writes LF, whatever the platform's separator")
        void outputUsesLf(String format) throws Exception {
            // JSON, XML and JSON Schema came out with CRLF on Windows and the
            // rest with LF, so saved files mixed the two by format.
            String json = "{\"a\":{\"b\":[1,2]},\"c\":\"x\",\"d\":[{\"e\":1}]}";
            assertThat(pipeline.renderFromJson(json, format, opts)).doesNotContain("\r").contains("\n");
        }

        @Test @DisplayName("Format keeps the document's own line breaks, so formatted text stays equal")
        void formatKeepsLineBreaks() throws Exception {
            String lf = "{\n  \"a\" : 1\n}";
            assertThat(pipeline.formatInput(lf, Formats.FMT_JSON, opts)).isEqualTo(lf);
            String crlf = "{\r\n  \"a\" : 1\r\n}";
            assertThat(pipeline.formatInput(crlf, Formats.FMT_JSON, opts)).isEqualTo(crlf);
            assertThat(pipeline.formatInput("<r>\r\n<a>1</a></r>", Formats.FMT_XML, opts))
                  .isEqualTo("<r>\r\n  <a>1</a>\r\n</r>\r\n");
            assertThat(pipeline.formatInput("a: 1\r\nb: 2\r\n", Formats.FMT_YAML, opts)).isEqualTo("a: 1\r\nb: 2\r\n");
            assertThat(pipeline.formatInput("a = 1\r\nb = 2\r\n", Formats.FMT_TOML, opts)).isEqualTo("a = 1\r\nb = 2\r\n");
        }
    }
}
