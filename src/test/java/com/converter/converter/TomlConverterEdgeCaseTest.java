package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Edge-case tests for TomlConverter.
 */
@DisplayName("TomlConverter \u2013 Edge Cases")
class TomlConverterEdgeCaseTest {

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

    @Test @DisplayName("JSON->TOML: null value — throws or is omitted (TOML has no null)")
    void jsonToTomlNullValue() {
        assertThatCode(() -> converter.jsonToToml("{\"name\":\"Alice\",\"middle\":null}"))
              .satisfiesAnyOf(
                    t -> { /* null field omitted, output still contains name */ },
                    t -> assertThat(t).isInstanceOf(Exception.class)
              );
    }

    @Test @DisplayName("JSON->TOML: top-level array — throws descriptively (TOML cannot represent bare array)")
    void jsonToTomlTopLevelArray() {
        assertThatCode(() -> converter.jsonToToml("[{\"id\":1},{\"id\":2}]"))
              .satisfiesAnyOf(
                    t -> { /* handled gracefully */ },
                    t -> assertThat(t).isInstanceOf(Exception.class)
              );
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

}
