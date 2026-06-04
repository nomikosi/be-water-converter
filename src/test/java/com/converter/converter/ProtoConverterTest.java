package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("ProtoConverter")
class ProtoConverterTest {

    private ProtoConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new ProtoConverter();
        json = new ObjectMapper();
    }

    // ── Proto -> JSON ─────────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: simple message with scalar fields")
    void protoToJsonSimple() throws Exception {
        String proto = "message Person { string name = 1; int32 age = 2; bool active = 3; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        JsonNode person = result.get("Person");
        assertThat(person).isNotNull();
        assertThat(person.get("name").asText()).isEqualTo("");
        assertThat(person.get("age").asInt()).isEqualTo(0);
        assertThat(person.get("active").asBoolean()).isFalse();
    }

    @Test @DisplayName("Proto->JSON: repeated field becomes JSON array")
    void protoToJsonRepeated() throws Exception {
        JsonNode result = json.readTree(converter.protoToJson("message Team { string teamName = 1; repeated string members = 2; }"));
        assertThat(result.path("Team").path("members").isArray()).isTrue();
    }

    @Test @DisplayName("Proto->JSON: multiple messages parsed independently")
    void protoToJsonMultipleMessages() throws Exception {
        String proto = "message Address { string street = 1; string city = 2; } message Person { string name = 1; int32 age = 2; Address address = 3; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("Address")).isTrue();
        assertThat(result.has("Person")).isTrue();
        assertThat(result.path("Address").path("city").asText()).isEqualTo("");
    }

    @Test @DisplayName("Proto->JSON: all scalar types map to correct JSON defaults")
    void protoToJsonAllTypes() throws Exception {
        String proto = "message AllTypes { string strField = 1; int32 intField = 2; int64 longField = 3; float floatField = 4; double doubleField = 5; bool boolField = 6; bytes bytesField = 7; }";
        JsonNode msg = json.readTree(converter.protoToJson(proto)).get("AllTypes");
        assertThat(msg.get("strField").asText()).isEqualTo("");
        assertThat(msg.get("intField").asInt()).isEqualTo(0);
        assertThat(msg.get("boolField").asBoolean()).isFalse();
    }

    @Test @DisplayName("Proto->JSON: comments stripped before parsing")
    void protoToJsonCommentsStripped() throws Exception {
        String proto = "// comment\nmessage User { string email = 1; // inline\n int32 id = 2; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("User")).isTrue();
        assertThat(result.path("User").path("email").asText()).isEqualTo("");
    }

    @Test @DisplayName("Proto->JSON: no message blocks throws descriptive error")
    void protoToJsonNoMessages() {
        assertThatThrownBy(() -> converter.protoToJson("syntax = \"proto3\";"))
            .isInstanceOf(Exception.class).hasMessageContaining("message");
    }

    // ── JSON -> Proto ─────────────────────────────────────────────────────

    @Test @DisplayName("JSON->Proto: flat object produces proto3 message")
    void jsonToProtoFlat() throws Exception {
        String result = converter.jsonToProto("{\"name\":\"Alice\",\"age\":30,\"active\":true}");
        assertThat(result).contains("syntax = \"proto3\"").contains("message Root")
            .contains("string name").contains("int32 age").contains("bool active");
    }

    @Test @DisplayName("JSON->Proto: nested object generates nested message")
    void jsonToProtoNested() throws Exception {
        String result = converter.jsonToProto("{\"person\":{\"name\":\"Bob\",\"age\":25}}");
        assertThat(result).contains("message Root").contains("message Person").contains("Person person");
    }

    @Test @DisplayName("JSON->Proto: array of objects generates repeated message")
    void jsonToProtoArrayObjects() throws Exception {
        String result = converter.jsonToProto("{\"items\":[{\"id\":1,\"label\":\"A\"}]}");
        assertThat(result).containsIgnoringCase("repeated").containsIgnoringCase("items");
    }

    @Test @DisplayName("JSON->Proto: array of scalars generates repeated primitive")
    void jsonToProtoArrayScalars() throws Exception {
        String result = converter.jsonToProto("{\"scores\":[10,20,30]}");
        assertThat(result).contains("repeated int32 scores");
    }

    @Test @DisplayName("JSON->Proto: root array uses first element as schema")
    void jsonToProtoRootArray() throws Exception {
        String result = converter.jsonToProto("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
        assertThat(result).contains("message Root").contains("int32 id").contains("string name");
    }

    @Test @DisplayName("JSON->Proto: field numbers are sequential starting at 1")
    void jsonToProtoFieldNumbers() throws Exception {
        String result = converter.jsonToProto("{\"a\":\"x\",\"b\":1,\"c\":true}");
        assertThat(result).contains("= 1;").contains("= 2;").contains("= 3;");
    }

    @Test @DisplayName("JSON->Proto: complex menu JSON generates full message hierarchy")
    void jsonToProtoMenu() throws Exception {
        String input = "{\"menu\":{\"id\":\"file\",\"value\":\"File\",\"popup\":{\"menuitem\":[{\"value\":\"New\",\"onclick\":\"CreateNewDoc()\"}]}}}";
        String result = converter.jsonToProto(input);
        assertThat(result).contains("message Root").contains("message Menu").contains("message Popup");
        assertThat(result).contains("string value").contains("string onclick");
    }

    @Test @DisplayName("JSON->Proto: double field uses correct proto type")
    void jsonToProtoDouble() throws Exception {
        String result = converter.jsonToProto("{\"price\":19.99}");
        assertThat(result).satisfiesAnyOf(
            r -> assertThat(r).contains("double price"),
            r -> assertThat(r).contains("float price")
        );
    }
}
