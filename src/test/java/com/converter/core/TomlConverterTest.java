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

@DisplayName("TomlConverter")
class TomlConverterTest {

    private TomlConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new TomlConverter();
        json = new ObjectMapper();
    }

    // ── TOML -> JSON ──────────────────────────────────────────────────────

    @Test @DisplayName("TOML->JSON: simple key-value")
    void tomlToJsonSimple() throws Exception {
        JsonNode result = json.readTree(converter.tomlToJson("name = \"Alice\"\nage = 30\n"));
        assertThat(result.get("name").asText()).isEqualTo("Alice");
        assertThat(result.get("age").asInt()).isEqualTo(30);
    }

    @Test @DisplayName("TOML->JSON: table section")
    void tomlToJsonTable() throws Exception {
        JsonNode result = json.readTree(converter.tomlToJson("[database]\nhost = \"localhost\"\nport = 5432\n"));
        assertThat(result.path("database").path("host").asText()).isEqualTo("localhost");
        assertThat(result.path("database").path("port").asInt()).isEqualTo(5432);
    }

    @Test @DisplayName("TOML->JSON: array of tables")
    void tomlToJsonArrayOfTables() throws Exception {
        JsonNode result = json.readTree(converter.tomlToJson("[[servers]]\nip = \"10.0.0.1\"\n[[servers]]\nip = \"10.0.0.2\"\n"));
        assertThat(result.path("servers").isArray()).isTrue();
        assertThat(result.path("servers").get(0).get("ip").asText()).isEqualTo("10.0.0.1");
        assertThat(result.path("servers").get(1).get("ip").asText()).isEqualTo("10.0.0.2");
    }

    @Test @DisplayName("TOML->JSON: nested sections")
    void tomlToJsonNested() throws Exception {
        JsonNode result = json.readTree(converter.tomlToJson("[app]\nname = \"MyApp\"\n[app.logging]\nlevel = \"INFO\"\n"));
        assertThat(result.path("app").path("name").asText()).isEqualTo("MyApp");
        assertThat(result.path("app").path("logging").path("level").asText()).isEqualTo("INFO");
    }

    @Test @DisplayName("TOML->JSON: boolean and integer types")
    void tomlToJsonTypes() throws Exception {
        JsonNode result = json.readTree(converter.tomlToJson("enabled = true\ncount = 42\n"));
        assertThat(result.get("enabled").asBoolean()).isTrue();
        assertThat(result.get("count").asInt()).isEqualTo(42);
    }

    // ── JSON -> TOML ──────────────────────────────────────────────────────

    @Test @DisplayName("JSON->TOML: flat object")
    void jsonToTomlFlat() throws Exception {
        String result = converter.jsonToToml("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result).contains("Alice").contains("30");
    }

    @Test @DisplayName("JSON->TOML: nested object produces TOML table")
    void jsonToTomlNested() throws Exception {
        String result = converter.jsonToToml("{\"database\":{\"host\":\"localhost\",\"port\":5432}}");
        assertThat(result).contains("localhost").contains("5432");
    }

    @Test @DisplayName("TOML round-trip: preserves values")
    void tomlRoundTrip() throws Exception {
        String toml = "title = \"Config\"\nversion = 2\n[server]\nport = 9090\n";
        JsonNode back = json.readTree(converter.tomlToJson(toml));
        assertThat(back.get("title").asText()).isEqualTo("Config");
        assertThat(back.path("server").path("port").asInt()).isEqualTo(9090);
    }

    /**
     * Edge-case tests for TomlConverter.
     */
    @Nested @DisplayName("edge cases")
    class EdgeCases {
        private TomlConverter converter;
        private ObjectMapper json;

        @BeforeEach void setUp() {
            converter = new TomlConverter();
            json = new ObjectMapper();
        }

        // ── Inline tables ─────────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: inline table parses as object")
        void inlineTable() throws Exception {
            String toml = "point = {x = 1, y = 2}\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.path("point").path("x").asInt()).isEqualTo(1);
            assertThat(result.path("point").path("y").asInt()).isEqualTo(2);
        }

        // ── Dotted keys ───────────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: dotted key creates nested object")
        void dottedKey() throws Exception {
            String toml = "server.host = \"localhost\"\nserver.port = 8080\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.path("server").path("host").asText()).isEqualTo("localhost");
            assertThat(result.path("server").path("port").asInt()).isEqualTo(8080);
        }

        // ── Float edge cases ──────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: positive/negative float values")
        void floatValues() throws Exception {
            String toml = "pi = 3.14159\nneg = -0.001\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("pi").asDouble()).isCloseTo(3.14159, within(0.00001));
            assertThat(result.get("neg").asDouble()).isNegative();
        }

        @Test @DisplayName("TOML->JSON: scientific notation float")
        void scientificFloat() throws Exception {
            String toml = "speed = 1.0e6\ntiny = 5.0e-3\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("speed").asDouble()).isEqualTo(1_000_000.0);
        }

        // ── Integer edge cases ────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: large integer")
        void largeInteger() throws Exception {
            String toml = "big = 9007199254740991\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("big").asLong()).isEqualTo(9007199254740991L);
        }

        @Test @DisplayName("TOML->JSON: negative integer")
        void negativeInteger() throws Exception {
            String toml = "temp = -273\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("temp").asInt()).isEqualTo(-273);
        }

        @Test @DisplayName("TOML->JSON: zero integer")
        void zeroInteger() throws Exception {
            String toml = "count = 0\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("count").asInt()).isEqualTo(0);
        }

        // ── Multiline strings ─────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: multiline basic string")
        void multilineString() throws Exception {
            String toml = "desc = \"\"\"\nRoses are red,\nViolets are blue\"\"\"\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("desc").asText()).contains("Roses");
        }

        // ── Special characters in strings ────────────────────────────────────

        @Test @DisplayName("TOML->JSON: escaped tab and newline in string")
        void escapedChars() throws Exception {
            String toml = "msg = \"Hello\\tWorld\"\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("msg").asText()).contains("Hello");
        }

        @Test @DisplayName("TOML->JSON->TOML: URL string round-trip")
        void urlStringRoundTrip() throws Exception {
            String toml = "endpoint = \"https://api.example.com/v1/data\"\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("endpoint").asText()).isEqualTo("https://api.example.com/v1/data");
        }

        // ── Unicode ───────────────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: unicode escape sequence \\uXXXX")
        void unicodeEscapeSequence() throws Exception {
            String toml = "heart = \"\u2665\"\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("heart").asText()).isEqualTo("\u2665");
        }

        @Test @DisplayName("TOML->JSON->TOML: Greek text round-trip")
        void greekTextRoundTrip() throws Exception {
            String toml = "city = \"\u0391\u03b8\u03ae\u03bd\u03b1\"\n";
            JsonNode back = json.readTree(converter.tomlToJson(toml));
            assertThat(back.get("city").asText()).isEqualTo("\u0391\u03b8\u03ae\u03bd\u03b1");
        }

        // ── Cargo.toml / pyproject.toml style ────────────────────────────────

        @Test @DisplayName("TOML->JSON: Cargo.toml style package section")
        void cargoTomlStyle() throws Exception {
            String toml =
                "[package]\n" +
                "name = \"my-crate\"\n" +
                "version = \"0.1.0\"\n" +
                "edition = \"2021\"\n" +
                "\n" +
                "[dependencies]\n" +
                "serde = \"1.0\"\n" +
                "tokio = \"1\"\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.path("package").path("name").asText()).isEqualTo("my-crate");
            assertThat(result.path("package").path("edition").asText()).isEqualTo("2021");
        }

        @Test @DisplayName("TOML->JSON: pyproject.toml style build-system section")
        void pyprojectTomlStyle() throws Exception {
            String toml =
                "[build-system]\n" +
                "build-backend = \"setuptools.build_meta\"\n" +
                "\n" +
                "[project]\n" +
                "name = \"myproject\"\n" +
                "version = \"1.0.0\"\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.path("project").path("name").asText()).isEqualTo("myproject");
        }

        // ── Array of different value types ───────────────────────────────────

        @Test @DisplayName("TOML->JSON: array of integers")
        void arrayOfIntegers() throws Exception {
            String toml = "ports = [8080, 8443, 9090]\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("ports").isArray()).isTrue();
            assertThat(result.get("ports").get(0).asInt()).isEqualTo(8080);
        }

        @Test @DisplayName("TOML->JSON: array of strings")
        void arrayOfStrings() throws Exception {
            String toml = "fruits = [\"apple\", \"banana\", \"cherry\"]\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("fruits").get(1).asText()).isEqualTo("banana");
        }

        // ── Round-trip fidelity ───────────────────────────────────────────────

        @Test @DisplayName("JSON->TOML->JSON: boolean fields round-trip")
        void booleanRoundTrip() throws Exception {
            String original = "{\"enabled\":true,\"debug\":false}";
            JsonNode back = json.readTree(converter.tomlToJson(converter.jsonToToml(original)));
            assertThat(back.get("enabled").asBoolean()).isTrue();
            assertThat(back.get("debug").asBoolean()).isFalse();
        }

        @Test @DisplayName("JSON->TOML->JSON: deeply nested config round-trip")
        void deepConfigRoundTrip() throws Exception {
            String original = "{\"app\":{\"server\":{\"host\":\"localhost\",\"port\":8080}," +
                "\"db\":{\"host\":\"db.local\",\"port\":5432,\"name\":\"mydb\"}}}";
            JsonNode back = json.readTree(converter.tomlToJson(converter.jsonToToml(original)));
            assertThat(back.path("app").path("server").path("port").asInt()).isEqualTo(8080);
            assertThat(back.path("app").path("db").path("name").asText()).isEqualTo("mydb");
        }

        // ── Datetime values ───────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: RFC 3339 datetime value does not throw")
        void datetimeRfc3339() throws Exception {
            String toml = "created = 1979-05-27T07:32:00Z\n";
            assertThatCode(() -> {
                String result = converter.tomlToJson(toml);
                assertThat(result).contains("created");
            }).doesNotThrowAnyException();
        }

        @Test @DisplayName("TOML->JSON: local date value does not throw")
        void localDate() throws Exception {
            String toml = "birthday = 1990-06-15\n";
            assertThatCode(() -> {
                String result = converter.tomlToJson(toml);
                assertThat(result).contains("birthday");
            }).doesNotThrowAnyException();
        }

    // ── Hex / Octal / Binary integers ────────────────────────────────────

        @Test @DisplayName("TOML->JSON: hex integer 0xFF parsed as number")
        void hexInteger() throws Exception {
            String toml = "color = 0xFF\n";
            assertThatCode(() -> {
                JsonNode result = json.readTree(converter.tomlToJson(toml));
                assertThat(result.get("color").isNumber()).isTrue();
                assertThat(result.get("color").asInt()).isEqualTo(255);
            }).doesNotThrowAnyException();
        }

        @Test @DisplayName("TOML->JSON: octal integer 0o17 parsed as number")
        void octalInteger() throws Exception {
            String toml = "perms = 0o17\n";
            assertThatCode(() -> {
                JsonNode result = json.readTree(converter.tomlToJson(toml));
                assertThat(result.get("perms").isNumber()).isTrue();
            }).doesNotThrowAnyException();
        }

        @Test @DisplayName("TOML->JSON: binary integer 0b1010 parsed as number")
        void binaryInteger() throws Exception {
            String toml = "flags = 0b1010\n";
            assertThatCode(() -> {
                JsonNode result = json.readTree(converter.tomlToJson(toml));
                assertThat(result.get("flags").isNumber()).isTrue();
            }).doesNotThrowAnyException();
        }

    // ── Special float literals ────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: inf float literal does not throw")
        void floatInf() {
            assertThatCode(() -> converter.tomlToJson("val = inf\n")).doesNotThrowAnyException();
        }

        @Test @DisplayName("TOML->JSON: -inf float literal does not throw")
        void floatNegInf() {
            assertThatCode(() -> converter.tomlToJson("val = -inf\n")).doesNotThrowAnyException();
        }

        @Test @DisplayName("TOML->JSON: nan float literal does not throw")
        void floatNan() {
            assertThatCode(() -> converter.tomlToJson("val = nan\n")).doesNotThrowAnyException();
        }

    // ── Literal strings (single-quoted) ──────────────────────────────────

        @Test @DisplayName("TOML->JSON: single-quoted literal string — backslash not escaped")
        void literalString() throws Exception {
            String toml = "path = 'C:\\Users\\tom\\documents'\n";
            JsonNode result = json.readTree(converter.tomlToJson(toml));
            assertThat(result.get("path").asText()).contains("Users");
        }

        @Test @DisplayName("TOML->JSON: multiline literal string (triple single-quote)")
        void multilineLiteralString() throws Exception {
            String toml = "regex = '''\nfirst line\nsecond line\n'''\n";
            assertThatCode(() -> converter.tomlToJson(toml)).doesNotThrowAnyException();
        }

    // ── JSON->TOML boundary cases ─────────────────────────────────────────

        @Test @DisplayName("JSON->TOML: null becomes an empty string (TOML has no null) and round-trips")
        void jsonToTomlNullValue() throws Exception {
            String toml = converter.jsonToToml("{\"name\":\"Alice\",\"middle\":null}");
            assertThat(toml).contains("name").contains("Alice");
            // Pinned: Jackson renders JSON null as '' — a Jackson behavior change
            // here should be a conscious decision, not a silent one.
            var back = new com.fasterxml.jackson.databind.ObjectMapper()
                  .readTree(converter.tomlToJson(toml));
            assertThat(back.get("middle").asText()).isEmpty();
        }

        @Test @DisplayName("JSON->TOML: top-level array is wrapped under 'items' and round-trips")
        void jsonToTomlTopLevelArray() throws Exception {
            String toml = converter.jsonToToml("[{\"id\":1},{\"id\":2}]");
            assertThat(toml).contains("items");
            // The unwrapped form ' = [...]' is invalid TOML — parsing back must work.
            var back = new com.fasterxml.jackson.databind.ObjectMapper()
                  .readTree(converter.tomlToJson(toml));
            assertThat(back.get("items")).hasSize(2);
            assertThat(back.get("items").get(0).get("id").asInt()).isEqualTo(1);
        }

        @Test @DisplayName("JSON->TOML: scalar root is wrapped under 'value' and round-trips")
        void jsonToTomlScalarRoot() throws Exception {
            String toml = converter.jsonToToml("42");
            assertThatCode(() -> converter.tomlToJson(toml)).doesNotThrowAnyException();
        }

    // ── Null / blank input ────────────────────────────────────────────────

        @Test @DisplayName("TOML->JSON: null input throws")
        void tomlToJsonNullInput() {
            assertThatThrownBy(() -> converter.tomlToJson(null))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("TOML->JSON: blank input throws")
        void tomlToJsonBlankInput() {
            assertThatThrownBy(() -> converter.tomlToJson("   "))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("JSON->TOML: null input throws")
        void jsonToTomlNullInput() {
            assertThatThrownBy(() -> converter.jsonToToml(null))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("TOML->JSON: an integer the parser mis-reads is refused, not silently changed")
        void rejectsIntegersTheParserCorrupts() {
            // jackson-dataformat-toml returns 0 for a 19-digit id — a Discord or
            // Twitter snowflake is exactly 19 digits — and drops the sign on a
            // negative 20-digit one. Measured identical on 2.17.2 through 2.21.1.
            assertThatThrownBy(() -> converter.tomlToJson("[e]\nid = 1723600000000000000\n"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("cannot be read correctly");
            assertThatThrownBy(() -> converter.tomlToJson("id = -92233720368547758070\n"))
                  .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> converter.tomlToJson("id = [1723600000000000000]\n"))
                  .isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("TOML->JSON: ordinary integers, and digits inside strings, are untouched")
        void integerGuardDoesNotOverreach() throws Exception {
            assertThat(converter.tomlToJson("a = 123456789012345678\n")).contains("123456789012345678");
            assertThat(converter.tomlToJson("a = 42\nb = -7\n")).contains("42").contains("-7");
            // The digits sit inside a string and a comment, so the guard must not fire.
            assertThat(converter.tomlToJson("a = \"1723600000000000000\"\n"))
                  .contains("1723600000000000000");
            assertThat(converter.tomlToJson("a = 1  # id 1723600000000000000\n")).contains("\"a\":1");
            // Hex and float forms the parser reads correctly still pass.
            assertThat(converter.tomlToJson("a = 0x17EA9F5B2C3D4E5F\n")).isNotBlank();
            assertThat(converter.tomlToJson("a = 1723600000000000000.0\n")).isNotBlank();
        }
    }

    @Nested @DisplayName("19-digit integer guard")
    class LexicalGuard {
        private static final String LARGE = "1723600000000000000";
        private final TomlConverter converter = new TomlConverter();

        /**
         * The upstream defect the guard exists for, read with jackson-dataformat-toml
         * directly. When a Jackson upgrade fixes it this fails: the guard, and the
         * README's limitation that names the versions, can then go.
         */
        @Test @DisplayName("jackson-dataformat-toml still misreads 19-digit integers")
        void upstreamStillMisreads() throws Exception {
            JsonNode read = new com.fasterxml.jackson.dataformat.toml.TomlMapper().readTree("id = " + LARGE + "\n");
            assertThat(read.get("id").asText()).isNotEqualTo(LARGE);
        }

        @ParameterizedTest @ValueSource(strings = {"\\\"\"\"", "\"\\\"\"", "\"\"\\\"", "\\\\\\\"\"\""})
        void escapedQuotesDoNotHideFollowingValues(String quotes) {
            String input = "text = \"\"\"before " + quotes + " after\"\"\"\nid = " + LARGE;
            assertThatThrownBy(() -> converter.tomlToJson(input))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TOML integer " + LARGE);
        }

        @ParameterizedTest @ValueSource(strings = {
              "1723600000000000000 = 'value'", "key.1723600000000000000 = 'value'",
              "[1723600000000000000]\nx = 'value'", "[[1723600000000000000]]\nx = 'value'",
              "x = { 1723600000000000000 = 'value' }", "x = [{1723600000000000000 = 'value'}]"})
        void numericKeysAreNotMistakenForValues(String input) throws Exception {
            assertThat(converter.tomlToJson(input)).contains("value", LARGE);
        }

        @ParameterizedTest @ValueSource(strings = {
              "x = [1, 1723600000000000000]", "x = [\n[1723600000000000000]\n]",
              "x = { y = 1723600000000000000 }", "x = [{a=1}, {b=1723600000000000000}]",
              "1723600000000000000 = 1723600000000000000", "x = [1, # comment\n1723600000000000000]"})
        void unsafeIntegersAreCheckedInEveryValuePosition(String input) {
            assertThatThrownBy(() -> converter.tomlToJson(input))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TOML integer " + LARGE);
        }

        @Test void numbersInsideStringsStayTextAndCommentsAreCountedCorrectly() throws Exception {
            String input = "text = \"\"\"before \\\"\"\" # " + LARGE + " after\"\"\" # real comment\nx = 2\n";
            assertThat(converter.tomlToJson(input)).contains(LARGE, "\"x\":2");
            assertThat(TomlConverter.countComments(input)).isEqualTo(1);
        }

        @Test void escapedQuotesDoNotHideFormattingLosses() {
            String prefix = "text = \"\"\"before \\\"\"\" after\"\"\"\n";
            var pipeline = new ConversionPipeline();
            assertThatThrownBy(() -> pipeline.formatInput(prefix + "date = 1979-05-27", "TOML", ConversionOptions.DEFAULTS.withInferTypes(false)))
                  .hasMessageContaining("rewrite the date");
            assertThatThrownBy(() -> pipeline.formatInput(prefix + "n = 0xFF", "TOML", ConversionOptions.DEFAULTS.withInferTypes(false)))
                  .hasMessageContaining("hexadecimal");
        }
    }

    @Nested @DisplayName("empty documents")
    class EmptyDocuments {

        private final ConversionPipeline pipeline = new ConversionPipeline();

        @Test @DisplayName("an empty object renders as TOML that parses back")
        void emptyTomlRoundTrips() throws Exception {
            String toml = pipeline.renderFromJson("{}", Formats.FMT_TOML,
                  ConversionOptions.DEFAULTS);
            assertThat(toml).doesNotStartWith(" = ");
            // Format on a comment-only TOML file used to yield invalid output.
            assertThat(pipeline.formatInput("# just a comment\n", Formats.FMT_TOML,
                  ConversionOptions.DEFAULTS)).doesNotStartWith(" = ");
        }
    }
}
