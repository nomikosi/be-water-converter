package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
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
}
