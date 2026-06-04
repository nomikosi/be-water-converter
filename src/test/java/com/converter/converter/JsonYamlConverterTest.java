package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonYamlConverter")
class JsonYamlConverterTest {

    private JsonYamlConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new JsonYamlConverter();
        json = new ObjectMapper();
    }

    // ── JSON -> YAML ──────────────────────────────────────────────────────

    @Test @DisplayName("JSON->YAML: flat object produces key: value lines")
    void jsonToYamlFlat() throws Exception {
        String result = converter.jsonToYaml("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result).contains("name: Alice").contains("age: 30");
    }

    @Test @DisplayName("JSON->YAML: nested object produces indented YAML")
    void jsonToYamlNested() throws Exception {
        String result = converter.jsonToYaml("{\"server\":{\"host\":\"localhost\",\"port\":8080}}");
        assertThat(result).contains("server:").contains("host: localhost").contains("port: 8080");
    }

    @Test @DisplayName("JSON->YAML: array of strings")
    void jsonToYamlArrayStrings() throws Exception {
        String result = converter.jsonToYaml("{\"colors\":[\"red\",\"green\",\"blue\"]}");
        assertThat(result).contains("colors:").contains("- red").contains("- green");
    }

    @Test @DisplayName("JSON->YAML: array of objects")
    void jsonToYamlArrayObjects() throws Exception {
        String result = converter.jsonToYaml("{\"users\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]}");
        assertThat(result).contains("- id: 1").contains("name: Alice");
    }

    @Test @DisplayName("JSON->YAML: boolean and numeric values")
    void jsonToYamlBoolNum() throws Exception {
        String result = converter.jsonToYaml("{\"enabled\":true,\"debug\":false,\"timeout\":30}");
        assertThat(result).contains("enabled: true").contains("debug: false").contains("timeout: 30");
    }

    @Test @DisplayName("JSON->YAML: complex nested config-like structure")
    void jsonToYamlComplex() throws Exception {
        String input = "{\"app\":{\"name\":\"MyApp\",\"database\":{\"host\":\"db.local\",\"port\":5432},\"features\":[\"auth\",\"logging\"]}}";
        String result = converter.jsonToYaml(input);
        assertThat(result).contains("name: MyApp").contains("port: 5432").contains("- auth");
    }

    // ── YAML -> JSON ──────────────────────────────────────────────────────

    @Test @DisplayName("YAML->JSON: simple key-value pairs")
    void yamlToJsonSimple() throws Exception {
        JsonNode result = json.readTree(converter.yamlToJson("name: Alice\nage: 30\n"));
        assertThat(result.get("name").asText()).isEqualTo("Alice");
        assertThat(result.get("age").asInt()).isEqualTo(30);
    }

    @Test @DisplayName("YAML->JSON: nested YAML block")
    void yamlToJsonNested() throws Exception {
        JsonNode result = json.readTree(converter.yamlToJson("server:\n  host: localhost\n  port: 8080\n"));
        assertThat(result.path("server").path("host").asText()).isEqualTo("localhost");
        assertThat(result.path("server").path("port").asInt()).isEqualTo(8080);
    }

    @Test @DisplayName("YAML->JSON: YAML list becomes JSON array")
    void yamlToJsonList() throws Exception {
        JsonNode result = json.readTree(converter.yamlToJson("tags:\n  - java\n  - spring\n  - postgres\n"));
        assertThat(result.get("tags").isArray()).isTrue();
        assertThat(result.get("tags").get(0).asText()).isEqualTo("java");
    }

    @Test @DisplayName("YAML->JSON: round-trip preserves structure")
    void yamlRoundTrip() throws Exception {
        String original = "{\"service\":{\"name\":\"api\",\"replicas\":3}}";
        JsonNode back = json.readTree(converter.yamlToJson(converter.jsonToYaml(original)));
        assertThat(back.path("service").path("replicas").asInt()).isEqualTo(3);
    }
}
