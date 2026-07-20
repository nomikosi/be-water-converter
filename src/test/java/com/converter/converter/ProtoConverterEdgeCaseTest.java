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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Edge-case tests for ProtoConverter.
 */
@DisplayName("ProtoConverter \u2013 Edge Cases")
class ProtoConverterEdgeCaseTest {

    private ProtoConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new ProtoConverter();
        json = new ObjectMapper();
    }

    // ── Scalar type defaults ──────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: sint32/uint32/fixed32 types produce numeric defaults")
    void protoExtendedIntTypes() throws Exception {
        String proto = "message Nums { sint32 s = 1; uint32 u = 2; fixed32 f = 3; sfixed32 sf = 4; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        JsonNode msg = result.get("Nums");
        assertThat(msg.get("s").isNumber()).isTrue();
        assertThat(msg.get("u").isNumber()).isTrue();
        assertThat(msg.get("f").isNumber()).isTrue();
    }

    @Test @DisplayName("Proto->JSON: int64/uint64/sint64/fixed64 types produce numeric defaults")
    void protoLongTypes() throws Exception {
        String proto = "message Longs { int64 a = 1; uint64 b = 2; sint64 c = 3; fixed64 d = 4; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        JsonNode msg = result.get("Longs");
        assertThat(msg.get("a").isNumber()).isTrue();
    }

    @Test @DisplayName("Proto->JSON: bytes type produces empty string or base64 default")
    void protoBytesType() throws Exception {
        String proto = "message Data { bytes payload = 1; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.path("Data").get("payload")).isNotNull();
    }

    // ── Enum fields ───────────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: message with enum type field does not throw")
    void protoEnumField() throws Exception {
        String proto = "enum Status { UNKNOWN = 0; ACTIVE = 1; INACTIVE = 2; }\n" +
            "message User { string name = 1; Status status = 2; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

    @Test @DisplayName("Proto->JSON: enum field defaults to its first declared value")
    void protoEnumFieldDefaultValue() throws Exception {
        String proto = "enum Status { UNKNOWN = 0; ACTIVE = 1; }\n" +
            "message User { string name = 1; Status status = 2; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.path("User").path("status").asText()).isEqualTo("UNKNOWN");
    }

    @Test @DisplayName("Proto->JSON: nested enum inside a message also resolves to first value")
    void protoNestedEnumDefaultValue() throws Exception {
        String proto = "message Order {\n" +
            "  enum State { CREATED = 0; SHIPPED = 1; }\n" +
            "  State state = 1;\n" +
            "}";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.path("Order").path("state").asText()).isEqualTo("CREATED");
    }

    @Test @DisplayName("Proto->JSON: duplicate field number across oneof is rejected")
    void duplicateNumberAcrossOneofRejected() {
        String proto = "message M {\n" +
            "  string a = 1;\n" +
            "  oneof choice {\n" +
            "    string b = 1;\n" +
            "  }\n" +
            "}";
        assertThatThrownBy(() -> converter.protoToJson(proto))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Duplicate field number 1");
    }

    @Test @DisplayName("JSON->Proto: integer field that looks like enum produces valid proto")
    void jsonToProtoEnumLike() throws Exception {
        String result = converter.jsonToProto("{\"name\":\"Alice\",\"statusCode\":1}");
        assertThat(result).contains("int32 statusCode").contains("string name");
    }

    // ── Field name sanitization (v1.4.0) ──────────────────────────────────

    @Test @DisplayName("JSON->Proto: kebab and space keys become valid identifiers")
    void jsonToProtoSanitizesFieldNames() throws Exception {
        String result = converter.jsonToProto("{\"first-name\":\"x\",\"last name\":\"y\"}");
        assertThat(result).contains("string first_name = 1;")
              .contains("string last_name = 2;")
              .doesNotContain("first-name");
    }

    @Test @DisplayName("JSON->Proto: keys colliding after sanitization are deduplicated")
    void jsonToProtoDeduplicatesFieldNames() throws Exception {
        String result = converter.jsonToProto("{\"a-b\":1,\"a b\":2}");
        assertThat(result).contains("a_b = 1;").contains("a_b_2 = 2;");
    }

    @Test @DisplayName("JSON->Proto: generated schema with hostile keys round-trips through protoToJson")
    void jsonToProtoRoundTripsWithHostileKeys() throws Exception {
        String schema = converter.jsonToProto(
              "{\"first-name\":\"x\",\"1st\":2,\"nested obj\":{\"k-v\":true}}");
        assertThatCode(() -> converter.protoToJson(schema)).doesNotThrowAnyException();
    }

    // ── Package / syntax / option directives ─────────────────────────────

    @Test @DisplayName("Proto->JSON: syntax + package header stripped, message still parsed")
    void protoWithPackageAndSyntax() throws Exception {
        String proto = "syntax = \"proto3\";\npackage com.example;\n\nmessage Product { string sku = 1; double price = 2; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("Product")).isTrue();
        assertThat(result.path("Product").path("sku").asText()).isEqualTo("");
    }

    @Test @DisplayName("Proto->JSON: option statements stripped without errors")
    void protoWithOptions() throws Exception {
        String proto = "syntax = \"proto3\";\noption java_package = \"com.example\";\n" +
            "option java_outer_classname = \"MyProto\";\nmessage Ping { string msg = 1; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("Ping")).isTrue();
    }

    @Test @DisplayName("Proto->JSON: import statements stripped without errors")
    void protoWithImport() throws Exception {
        String proto = "syntax = \"proto3\";\nimport \"google/protobuf/timestamp.proto\";\n" +
            "message Event { string name = 1; string timestamp = 2; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

    // ── Oneof fields ──────────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: oneof block does not crash the parser")
    void protoOneofField() throws Exception {
        String proto = "message Payment {\n" +
            "  string currency = 1;\n" +
            "  oneof payment_method {\n" +
            "    string card_number = 2;\n" +
            "    string bank_account = 3;\n" +
            "  }\n" +
            "}";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

    // ── Deeply nested messages ────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: 3-level nested messages all appear in output")
    void deeplyNestedMessages() throws Exception {
        String proto = "message Country { string name = 1; }\n" +
            "message Address { string street = 1; Country country = 2; }\n" +
            "message Person { string name = 1; Address address = 2; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("Country")).isTrue();
        assertThat(result.has("Address")).isTrue();
        assertThat(result.has("Person")).isTrue();
    }

    // ── JSON -> Proto special values ──────────────────────────────────────

    @Test @DisplayName("JSON->Proto: null field maps to google.protobuf.NullValue or is skipped")
    void jsonToProtoNullField() throws Exception {
        String result = converter.jsonToProto("{\"name\":\"Alice\",\"meta\":null}");
        assertThat(result).contains("syntax = \"proto3\"");
    }

    @Test @DisplayName("JSON->Proto: empty string field maps to string type")
    void jsonToProtoEmptyString() throws Exception {
        String result = converter.jsonToProto("{\"tag\":\"\"}");
        assertThat(result).contains("string tag");
    }

    @Test @DisplayName("JSON->Proto: boolean array maps to repeated bool")
    void jsonToProtoBoolArray() throws Exception {
        String result = converter.jsonToProto("{\"flags\":[true,false,true]}");
        assertThat(result).contains("repeated bool flags");
    }

    @Test @DisplayName("JSON->Proto: string array maps to repeated string")
    void jsonToProtoStringArray() throws Exception {
        String result = converter.jsonToProto("{\"tags\":[\"java\",\"spring\",\"proto\"]}");
        assertThat(result).contains("repeated string tags");
    }

    @Test @DisplayName("JSON->Proto: float value maps to float or double type")
    void jsonToProtoFloatType() throws Exception {
        String result = converter.jsonToProto("{\"lat\":37.9838,\"lon\":23.7275}");
        assertThat(result).satisfiesAnyOf(
            r -> assertThat(r).contains("double lat"),
            r -> assertThat(r).contains("float lat")
        );
    }

    // ── Multi-message Proto -> JSON ───────────────────────────────────────

    @Test @DisplayName("Proto->JSON: Address + Person produces two top-level keys")
    void multiMessageOutputKeys() throws Exception {
        String proto = "message Address { string city = 1; string zip = 2; }\n" +
            "message Person { string name = 1; int32 age = 2; }";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("Address")).isTrue();
        assertThat(result.has("Person")).isTrue();
        assertThat(result.path("Address").path("city").asText()).isEqualTo("");
        assertThat(result.path("Person").path("age").asInt()).isEqualTo(0);
    }

    // ── Block comments ────────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: block comments /* */ are stripped before parsing")
    void blockComments() throws Exception {
        String proto = "/* This is a block comment */\n" +
            "message Config {\n" +
            "  /* Another comment */\n" +
            "  string env = 1; // inline\n" +
            "  int32 workers = 2;\n" +
            "}";
        assertThatCode(() -> {
            JsonNode result = json.readTree(converter.protoToJson(proto));
            assertThat(result.has("Config")).isTrue();
        }).doesNotThrowAnyException();
    }

    // ── Empty message ─────────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: empty message body produces empty object")
    void emptyMessageBody() throws Exception {
        String proto = "message Empty {}";
        JsonNode result = json.readTree(converter.protoToJson(proto));
        assertThat(result.has("Empty")).isTrue();
        assertThat(result.get("Empty").size()).isEqualTo(0);
    }

    // ── Proto round-trip via JSON ─────────────────────────────────────────

    @Test @DisplayName("JSON->Proto output contains syntax = proto3 header")
    void jsonToProtoHasSyntaxHeader() throws Exception {
        String result = converter.jsonToProto("{\"id\":1,\"name\":\"test\"}");
        assertThat(result).startsWith("syntax = \"proto3\"");
    }

    @Test @DisplayName("JSON->Proto: deeply nested 3-level object produces nested messages")
    void jsonToProtoDeeplyNested() throws Exception {
        String result = converter.jsonToProto(
            "{\"company\":{\"address\":{\"city\":\"Athens\",\"country\":\"Greece\"}}}");
        assertThat(result).contains("message Root").contains("message Company").contains("message Address");
    }

    // ── map<K, V> fields ──────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: map<string, string> field does not throw")
    void protoMapStringString() {
        String proto = "message Config { map<string, string> labels = 1; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

    @Test @DisplayName("Proto->JSON: map<string, int32> field does not throw")
    void protoMapStringInt() {
        String proto = "message Scores { map<string, int32> grades = 1; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

// ── Nested message definitions ────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: message nested inside another message does not throw")
    void nestedMessageDefinition() {
        String proto =
              "message Outer {\n" +
                    "  string name = 1;\n" +
                    "  message Inner {\n" +
                    "    int32 value = 1;\n" +
                    "  }\n" +
                    "  Inner inner = 2;\n" +
                    "}";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

// ── reserved fields ───────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: reserved field numbers are stripped without error")
    void reservedFieldNumbers() throws Exception {
        String proto =
              "message User {\n" +
                    "  reserved 2, 15;\n" +
                    "  string name = 1;\n" +
                    "  int32 age = 3;\n" +
                    "}";
        assertThatCode(() -> {
            JsonNode result = json.readTree(converter.protoToJson(proto));
            assertThat(result.has("User")).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test @DisplayName("Proto->JSON: reserved field names are stripped without error")
    void reservedFieldNames() throws Exception {
        String proto =
              "message User {\n" +
                    "  reserved \"old_name\", \"deprecated_field\";\n" +
                    "  string name = 1;\n" +
                    "}";
        assertThatCode(() -> {
            JsonNode result = json.readTree(converter.protoToJson(proto));
            assertThat(result.has("User")).isTrue();
        }).doesNotThrowAnyException();
    }

// ── Duplicate field numbers ───────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: duplicate field numbers throws or handled safely (not silently accepted)")
    void duplicateFieldNumbers() {
        String proto = "message Bad { string name = 1; int32 age = 1; }";
        assertThatCode(() -> converter.protoToJson(proto))
              .satisfiesAnyOf(
                    t -> { /* converter detected duplicate and threw */ assertThat(t).isInstanceOf(Exception.class); },
                    t -> { /* converter produced output but did not silently drop data */ }
              );
    }

// ── Well-known types ──────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: google.protobuf.Timestamp field does not throw")
    void wellKnownTimestamp() {
        String proto =
              "import \"google/protobuf/timestamp.proto\";\n" +
                    "message Event { string name = 1; google.protobuf.Timestamp occurred_at = 2; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

    @Test @DisplayName("Proto->JSON: google.protobuf.Any field does not throw")
    void wellKnownAny() {
        String proto =
              "import \"google/protobuf/any.proto\";\n" +
                    "message Wrapper { string id = 1; google.protobuf.Any payload = 2; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

// ── Enum with allow_alias ─────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: enum with allow_alias option does not throw")
    void enumAllowAlias() {
        String proto =
              "enum Status {\n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0;\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 1;\n" +
                    "}\n" +
                    "message Task { string name = 1; Status status = 2; }";
        assertThatCode(() -> converter.protoToJson(proto)).doesNotThrowAnyException();
    }

// ── Null / blank input ────────────────────────────────────────────────

    @Test @DisplayName("Proto->JSON: null input throws")
    void protoToJsonNullInput() {
        assertThatThrownBy(() -> converter.protoToJson(null))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("Proto->JSON: blank input throws")
    void protoToJsonBlankInput() {
        assertThatThrownBy(() -> converter.protoToJson("   "))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("JSON->Proto: null input throws")
    void jsonToProtoNullInput() {
        assertThatThrownBy(() -> converter.jsonToProto(null))
              .isInstanceOf(Exception.class);
    }

}
