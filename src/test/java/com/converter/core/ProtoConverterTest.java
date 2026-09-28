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

    /**
     * Edge-case tests for ProtoConverter.
     */
    @Nested @DisplayName("edge cases")
    class EdgeCases {
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

        @Test @DisplayName("JSON->Proto: kebab and space keys become valid identifiers, and keep their key")
        void jsonToProtoSanitizesFieldNames() throws Exception {
            String result = converter.jsonToProto("{\"first-name\":\"x\",\"last name\":\"y\"}");
            // The original key survives only inside json_name; the identifier is clean.
            assertThat(result).contains("string first_name = 1 [json_name = \"first-name\"];")
                  .contains("string last_name = 2 [json_name = \"last name\"];")
                  .doesNotContain("string first-name");
        }

        @Test @DisplayName("JSON->Proto: keys colliding after sanitization are deduplicated")
        void jsonToProtoDeduplicatesFieldNames() throws Exception {
            String result = converter.jsonToProto("{\"a-b\":1,\"a b\":2}");
            assertThat(result).contains("a_b = 1 [json_name = \"a-b\"];")
                  .contains("a_b_2 = 2 [json_name = \"a b\"];");
        }

        @Test @DisplayName("JSON->Proto: nested messages colliding on name are deduplicated")
        void jsonToProtoDeduplicatesMessageNames() throws Exception {
            // "user" and "User" both want the message name "User"; emitting it twice
            // in one scope is invalid proto3. The key "User" also becomes a FIELD
            // named User, and protoc keeps nested types and fields in one symbol
            // table per message — so "message User" cannot be emitted here either.
            String result = converter.jsonToProto("{\"user\":{\"a\":1},\"User\":{\"b\":\"x\"}}");
            assertThat(result).contains("message User {").contains("message User2 {");
            // The fields may not be "user" and "User" either: protobuf 3.x refuses
            // names equal once lower-cased. The second keeps its key through json_name.
            assertThat(result).contains("User user = 1;")
                  .contains("User2 User_2 = 2 [json_name = \"User\"];");
        }

        @Test @DisplayName("JSON->Proto: colliding array-of-object message names are deduplicated")
        void jsonToProtoDeduplicatesRepeatedMessageNames() throws Exception {
            String result = converter.jsonToProto("{\"item\":{\"a\":1},\"Item\":[{\"b\":2}]}");
            assertThat(result).contains("message Item {").contains("message Item2 {")
                  .contains("Item item = 1;")
                  .contains("repeated Item2 Item_2 = 2 [json_name = \"Item\"];");
        }

        @Test @DisplayName("JSON->Proto: a message never takes the name of a sibling field")
        void jsonToProtoMessageNeverShadowsField() throws Exception {
            // protoc: '"Foo" is already defined in "Root"'. The wrapper messages made
            // this reachable for many more documents than the plain-object case did.
            assertThat(converter.jsonToProto("{\"Foo\":[[{\"x\":1}]]}"))
                  .contains("repeated FooRow Foo = 1;")
                  .doesNotContain("message Foo {");
            assertThat(converter.jsonToProto("{\"m\":[[1]],\"MRow\":1}"))
                  .doesNotContain("message MRow {")
                  .contains("int32 MRow = 2;");
        }

        @Test @DisplayName("JSON->Proto: a root array of scalars is refused without naming a type it never had")
        void jsonToProtoRootArrayDiagnostic() {
            // Unwrapping every level made the old message report "number" for a
            // document whose root the user wrote as an array.
            assertThatThrownBy(() -> converter.jsonToProto("[[1,2],[3,4]]"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("innermost element is: number");
            assertThatThrownBy(() -> converter.jsonToProto("42"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("but got: number");
        }

        @Test @DisplayName("JSON->Proto: absurd array nesting is refused rather than expanded")
        void jsonToProtoRejectsRunawayNesting() {
            // One wrapper message per level turned a 1.6 MB document into a 50 MB
            // schema of nothing but wrappers.
            String deep = "{\"k\":" + "[".repeat(40) + "1" + "]".repeat(40) + "}";
            assertThatThrownBy(() -> converter.jsonToProto(deep))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("nests arrays");
        }

        // ── String literals are not structural (comments/braces inside quotes) ──

        @Test @DisplayName("Proto->JSON: a brace inside a string literal is not a block opener")
        void protoBraceInsideStringLiteral() throws Exception {
            String result = converter.protoToJson(
                  "message M { string s = 1; }\noption x = \"{\";");
            assertThat(result).contains("\"M\"").contains("\"s\"");
        }

        @Test @DisplayName("Proto->JSON: '//' inside a string literal is not a comment")
        void protoSlashesInsideStringLiteral() throws Exception {
            String result = converter.protoToJson(
                  "option java_package = \"http://example.com\";\nmessage M { string s = 1; }");
            assertThat(result).contains("\"M\"").contains("\"s\"");
        }

        @Test @DisplayName("Proto->JSON: a quote inside a comment does not swallow the schema")
        void protoQuoteInsideComment() throws Exception {
            String result = converter.protoToJson(
                  "// don\"t let this quote start a string\nmessage M { string s = 1; }");
            assertThat(result).contains("\"M\"").contains("\"s\"");
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

        // ── Nested arrays: proto3 has no "repeated repeated" ─────────────────

        @Test @DisplayName("JSON->Proto: an array of arrays of objects keeps the object's fields")
        void jsonToProtoNestedArrayOfObjects() throws Exception {
            // Previously "repeated string matrix", losing field 'a' and a dimension.
            String result = converter.jsonToProto("{\"matrix\":[[{\"a\":1}]]}");
            assertThat(result)
                  .contains("message Matrix {")
                  .contains("int32 a = 1;")
                  .contains("message MatrixRow {")
                  .contains("repeated Matrix values = 1;")
                  .contains("repeated MatrixRow matrix = 1;")
                  .doesNotContain("repeated string matrix");
        }

        @Test @DisplayName("JSON->Proto: an array of arrays of scalars keeps the element type")
        void jsonToProtoNestedArrayOfScalars() throws Exception {
            assertThat(converter.jsonToProto("{\"m\":[[1,2]]}"))
                  .contains("message MRow {")
                  .contains("repeated int32 values = 1;")
                  .contains("repeated MRow m = 1;");
        }

        @Test @DisplayName("JSON->Proto: each extra array level gets its own wrapper message")
        void jsonToProtoThreeLevelArray() throws Exception {
            assertThat(converter.jsonToProto("{\"m\":[[[{\"a\":1}]]]}"))
                  .contains("message M {")
                  .contains("message MRow {")
                  .contains("repeated M values = 1;")
                  .contains("message MRow2 {")
                  .contains("repeated MRow values = 1;")
                  .contains("repeated MRow2 m = 1;");
        }

        @Test @DisplayName("JSON->Proto: a wrapper name colliding with a real key is deduplicated")
        void jsonToProtoWrapperNameCollision() throws Exception {
            // The synthesized MRow and the object under "mRow" both want that name.
            String result = converter.jsonToProto("{\"m\":[[{\"a\":1}]],\"mRow\":{\"z\":1}}");
            assertThat(result)
                  .contains("message MRow {")
                  .contains("message MRow2 {")
                  .contains("int32 z = 1;");
        }

        @Test @DisplayName("JSON->Proto: single-level arrays are unchanged")
        void jsonToProtoSingleLevelArraysUnchanged() throws Exception {
            assertThat(converter.jsonToProto("{\"m\":[1,2]}")).contains("repeated int32 m = 1;");
            assertThat(converter.jsonToProto("{\"m\":[]}")).contains("repeated string m = 1;");
            assertThat(converter.jsonToProto("{\"m\":[{\"a\":1}]}"))
                  .contains("message M {").contains("repeated M m = 1;");
        }

        @Test @DisplayName("JSON->Proto: a root array of arrays is unwrapped all the way down")
        void jsonToProtoRootArrayOfArrays() throws Exception {
            // The same shape one level in generates; the root must not disagree.
            assertThat(converter.jsonToProto("[[{\"id\":1}]]"))
                  .contains("message Root").contains("int32 id = 1;");
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
            assertThatThrownBy(() -> converter.protoToJson(proto))
                  .hasMessageContaining("Duplicate field number 1 in message 'Bad'");
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

    /**
     * Tests for the improved validation messages on malformed Protobuf input.
     */
    @Nested @DisplayName("validation messages")
    class Validation {
        private ProtoConverter converter;
        private ObjectMapper json;

        @BeforeEach void setUp() {
            converter = new ProtoConverter();
            json = new ObjectMapper();
        }

        @Test @DisplayName("Blank input fails with a descriptive message")
        void blankInput() {
            assertThatThrownBy(() -> converter.protoToJson("   "))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("empty");
        }

        @Test @DisplayName("Unbalanced braces are reported with counts")
        void unbalancedBraces() {
            String proto = "message Person { string name = 1; ";
            assertThatThrownBy(() -> converter.protoToJson(proto))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Unbalanced braces")
                  .hasMessageContaining("1 '{'")
                  .hasMessageContaining("0 '}'");
        }

        @Test @DisplayName("A well-formed field missing its ';' is reported, not silently dropped")
        void unterminatedFieldIsReported() {
            // STATEMENT_PATTERN does not need the semicolon but FIELD_PATTERN does,
            // so validation passed and extraction found nothing: the message came
            // back as {} with the field gone.
            assertThatThrownBy(() -> converter.protoToJson("message M {\n  string a = 1\n}"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("missing its terminating ';'");
        }

        @Test @DisplayName("A nested message nothing references is still validated")
        void unreferencedNestedMessageIsValidated() {
            // buildMessageNode validated only as it descended, so a nested message
            // no field pointed at was never checked at all.
            assertThatThrownBy(() -> converter.protoToJson(
                  "message Outer {\n  string ok = 1;\n  message Inner {\n    !!! garbage\n  }\n}"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Inner");
            assertThatThrownBy(() -> converter.protoToJson(
                  "message Outer {\n  string ok = 1;\n"
                  + "  message Inner {\n    string a = 1;\n    string b = 1;\n  }\n}"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Duplicate field number");
        }

        @Test @DisplayName("Malformed field reports the message name and offending statement")
        void malformedField() {
            String proto = "message Person { string name = 1; int32 age }";
            assertThatThrownBy(() -> converter.protoToJson(proto))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Person")
                  .hasMessageContaining("int32 age")
                  .hasMessageContaining("[repeated] <type> <name> = <number>;");
        }

        @Test @DisplayName("Field missing a type is rejected")
        void fieldMissingType() {
            String proto = "message Person { name = 1; }";
            assertThatThrownBy(() -> converter.protoToJson(proto))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Invalid field in message 'Person'");
        }

        @Test @DisplayName("Duplicate field numbers are rejected with the number and message name")
        void duplicateFieldNumbers() {
            String proto = "message Bad { string name = 1; int32 age = 1; }";
            assertThatThrownBy(() -> converter.protoToJson(proto))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Duplicate field number 1")
                  .hasMessageContaining("Bad");
        }

        @Test @DisplayName("'message' keyword without a parseable block gives a targeted hint")
        void messageKeywordButNoBlock() {
            String proto = "message { string name = 1; }";
            assertThatThrownBy(() -> converter.protoToJson(proto))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("could not parse any message block");
        }

        @Test @DisplayName("Valid schema with options, reserved fields, and comments still parses")
        void validSchemaStillParses() throws Exception {
            String proto = "syntax = \"proto3\";\n" +
                  "option java_package = \"com.example\";\n" +
                  "/* block comment */\n" +
                  "message User {\n" +
                  "  reserved 4, 9;\n" +
                  "  string name = 1; // inline comment\n" +
                  "  repeated string tags = 2;\n" +
                  "  google.protobuf.Timestamp created = 3;\n" +
                  "}";
            JsonNode result = json.readTree(converter.protoToJson(proto));
            assertThat(result.has("User")).isTrue();
            assertThat(result.path("User").path("tags").isArray()).isTrue();
        }

        @Test @DisplayName("JSON->Proto: scalar root is rejected with a descriptive message")
        void jsonToProtoScalarRoot() {
            assertThatThrownBy(() -> converter.jsonToProto("42"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("must be an object");
        }
    }

    @Nested @DisplayName("json_name")
    class JsonNames {
        private final ProtoConverter converter = new ProtoConverter();

        private JsonNode convert(String schema) throws Exception {
            return PivotJson.mapper().readTree(converter.protoToJson(schema));
        }

        @Test void generatedMappingsKeepAllOriginalKeys() throws Exception {
            String input = "{\"first-name\":\"Ada\",\"a b\":1,\"a_b\":2,\"\":true,\"nested obj\":{\"k-v\":1}}";
            JsonNode output = convert(converter.jsonToProto(input)).get("Root");
            assertThat(output.has("first-name")).isTrue();
            assertThat(output.get("a b").isInt()).isTrue();
            assertThat(output.get("a_b").isInt()).isTrue();
            assertThat(output.get("").isBoolean()).isTrue();
            assertThat(output.at("/nested obj/k-v").isInt()).isTrue();
            assertThat(output).hasSize(5);
        }

        @Test void generatedEscapesCannotAffectStructuralParsing() throws Exception {
            var input = PivotJson.mapper().createObjectNode();
            for (String key : new String[]{"bracket];{}", "quote\"slash\\", "//comment", "/*message X {}*/", "line\nbreak", "tab\tkey", "control\u0001", "é😀"})
                input.put(key, "sample");
            JsonNode output = convert(converter.jsonToProto(input.toString())).get("Root");
            assertThat(output.properties()).extracting(java.util.Map.Entry::getKey)
                  .containsExactlyElementsOf(input.properties().stream().map(java.util.Map.Entry::getKey).toList());
        }

        @Test void nestedAndOneofOptionsRetainOffsetsIntoTheSource() throws Exception {
            JsonNode root = convert("""

                  // Leading comment and nested blocks must not shift source offsets.
                  syntax = "proto3";
                  message Root {
                    message Child { string value = 1 [json_name = "child-value"]; }
                    enum Kind { K = 0; }
                    Child child = 1 [json_name = "nested child"];
                    repeated string tags = 2 [deprecated = true, json_name = "tag-list"];
                    map<string, string> entries = 3 [json_name = "entry-map"];
                    oneof choice {
                      string name = 4 [json_name = /* ignored \" } */ 'given-' "name"];
                    }
                  }
                  """).get("Root");
            assertThat(root.at("/nested child/child-value").isTextual()).isTrue();
            assertThat(root.get("tag-list").isArray()).isTrue();
            assertThat(root.get("entry-map").isObject()).isTrue();
            assertThat(root.get("given-name").isTextual()).isTrue();
        }

        @Test void byteAndUnicodeEscapesDecodeAsUtf8() throws Exception {
            JsonNode root = convert("message Root { string value = 1 [json_name = \"\\xC3\\251-\\u0061-\\U0001F600\"]; }").get("Root");
            assertThat(root.has("é-a-😀")).isTrue();
        }

        @Test void unrelatedOptionsAndCommentsDoNotSupplyJsonNames() throws Exception {
            JsonNode root = convert("""
                  message Root {
                    string value = 1 [(custom) = "json_name = 'wrong'", deprecated = true];
                    string other = 2 [/* json_name = "wrong" */ deprecated = true];
                  }
                  """).get("Root");
            assertThat(root.has("value")).isTrue();
            assertThat(root.has("other")).isTrue();
        }

        @ParameterizedTest @ValueSource(strings = {
              "json_name = 123", "json_name = \"bad\\q\"", "json_name = \"\\xFF\"",
              "json_name = \"a\", json_name = \"b\""})
        void invalidOrRepeatedMappingsFailClearly(String options) {
            assertThatThrownBy(() -> converter.protoToJson("message Root { string value = 1 [" + options + "]; }"))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("json_name");
        }

        @Test void collidingJsonNamesDoNotSilentlyDropFields() {
            assertThatThrownBy(() -> converter.protoToJson("""
                  message Root {
                    string first = 1 [json_name = "same"];
                    oneof choice { int32 second = 2 [json_name = "same"]; }
                  }
                  """))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate JSON field name");
        }
    }

    /**
     * Type names resolve the way protoc resolves them: a message's own nested
     * types first, then its enclosing messages', then the file's top level. One
     * flat registry keyed by simple name gave every same-named nested type the
     * last definition registered, and could not follow a dotted name at all.
     */
    @Nested @DisplayName("type-name scoping")
    class Scoping {
        private final ProtoConverter converter = new ProtoConverter();
        private final ObjectMapper json = new ObjectMapper();

        private JsonNode convert(String proto) throws Exception {
            return json.readTree(converter.protoToJson(proto));
        }

        @Test @DisplayName("same-named nested messages resolve to their own message's definition")
        void nestedMessagesAreScoped() throws Exception {
            JsonNode tree = convert("""
                  message A {
                    message Inner { int32 x = 1; }
                    Inner i = 1;
                  }
                  message B {
                    message Inner { string y = 1; }
                    Inner i = 1;
                  }
                  """);
            // Both used to get B's Inner, the last one registered.
            assertThat(tree.at("/A/i/x").isInt()).isTrue();
            assertThat(tree.at("/A/i").has("y")).isFalse();
            assertThat(tree.at("/B/i/y").isTextual()).isTrue();
            assertThat(tree.at("/B/i").has("x")).isFalse();
        }

        @Test @DisplayName("same-named nested enums resolve to their own message's definition")
        void nestedEnumsAreScoped() throws Exception {
            JsonNode tree = convert("""
                  message A {
                    enum Status { ACTIVE = 0; GONE = 1; }
                    Status s = 1;
                  }
                  message B {
                    enum Status { OFF = 0; ON = 1; }
                    Status s = 1;
                  }
                  """);
            assertThat(tree.at("/A/s").asText()).isEqualTo("ACTIVE");
            assertThat(tree.at("/B/s").asText()).isEqualTo("OFF");
        }

        @Test @DisplayName("a nested type shadows a top-level one only inside its own message")
        void nestedTypeShadowsLocally() throws Exception {
            JsonNode tree = convert("""
                  message Inner { bool top = 1; }
                  message A {
                    message Inner { int32 x = 1; }
                    Inner i = 1;
                  }
                  message C { Inner i = 1; }
                  """);
            assertThat(tree.at("/A/i").has("x")).isTrue();
            // C never declared an Inner of its own, so it sees the top-level one.
            assertThat(tree.at("/C/i").has("top")).isTrue();
            assertThat(tree.at("/Inner/top").isBoolean()).isTrue();
        }

        @Test @DisplayName("sibling nested types and an enclosing message's types are visible")
        void enclosingScopesAreSearched() throws Exception {
            JsonNode tree = convert("""
                  message Outer {
                    enum Kind { FIRST = 0; }
                    message X { int32 v = 1; }
                    message Y {
                      X x = 1;
                      Kind kind = 2;
                    }
                    Y y = 1;
                  }
                  """);
            assertThat(tree.at("/Outer/y/x/v").isInt()).isTrue();
            assertThat(tree.at("/Outer/y/kind").asText()).isEqualTo("FIRST");
        }

        @Test @DisplayName("dotted references descend into nested scopes")
        void dottedReferencesResolve() throws Exception {
            JsonNode tree = convert("""
                  message A {
                    message Inner { int32 x = 1; }
                    enum Kind { K = 0; }
                  }
                  message B {
                    A.Inner i = 1;
                    A.Kind k = 2;
                    pkg.A.Inner p = 3;
                    .A.Inner q = 4;
                    A a = 5;
                  }
                  """);
            // Every dotted reference used to produce an empty object.
            assertThat(tree.at("/B/i/x").isInt()).isTrue();
            assertThat(tree.at("/B/k").asText()).isEqualTo("K");
            assertThat(tree.at("/B/p").isEmpty()).isTrue(); // pkg was never declared
            assertThat(tree.at("/B/q/x").isInt()).isTrue();
            assertThat(tree.at("/B/a").isObject()).isTrue();
        }

        @Test @DisplayName("an unknown type, dotted or not, is still an empty object")
        void unknownTypesStayEmpty() throws Exception {
            JsonNode tree = convert("""
                  message M {
                    google.protobuf.Timestamp at = 1;
                    Nowhere n = 2;
                    A.Missing m = 3;
                  }
                  message A { int32 v = 1; }
                  """);
            assertThat(tree.at("/M/at").isObject()).isTrue();
            assertThat(tree.at("/M/at").isEmpty()).isTrue();
            assertThat(tree.at("/M/n").isEmpty()).isTrue();
            assertThat(tree.at("/M/m").isEmpty()).isTrue();
        }

        @Test @DisplayName("a recursive message bottoms out rather than looping")
        void recursionStillTerminates() throws Exception {
            JsonNode tree = convert("message Node { string v = 1; Node next = 2; }");
            assertThat(tree.at("/Node/next").isObject()).isTrue();
            assertThat(tree.at("/Node/next").isEmpty()).isTrue();
        }

        @Test @DisplayName("a oneof inside a nested message belongs to that message")
        void nestedOneofStaysNested() throws Exception {
            // Searched over the raw body, the inner oneof was added to the OUTER
            // message — and validated against its numbers, so "int32 x = 1" inside
            // B was reported as a duplicate of A's own field 1.
            JsonNode tree = convert("""
                  message A {
                    int32 a = 1;
                    message B {
                      oneof o { int32 x = 1; string y = 2; }
                    }
                    B b = 2;
                  }
                  """);
            assertThat(tree.at("/A").has("x")).isFalse();
            assertThat(tree.at("/A/b").has("x")).isTrue();
            assertThat(tree.at("/A/b").has("y")).isTrue();
        }

        @Test @DisplayName("field numbers protoc rejects are rejected here too")
        void illegalFieldNumbersAreRefused() {
            for (String schema : new String[]{
                  "message A { int32 a = 0; }",
                  "message A { int32 a = 19500; }",
                  "message A { int32 a = 536870912; }",
                  "message A { int32 a = 99999999999999999999; }"}) {
                assertThatThrownBy(() -> converter.protoToJson(schema))
                      .describedAs(schema)
                      .isInstanceOf(IllegalArgumentException.class)
                      .hasMessageContaining("is not allowed");
            }
            assertThatCode(() -> converter.protoToJson(
                  "message A { int32 a = 1; int32 b = 536870911; int32 c = 18999; int32 d = 20000; }"))
                  .doesNotThrowAnyException();
        }

        @Test @DisplayName("JSON->Proto keeps a key its field name could not spell, via json_name")
        void jsonNameCarriesTheOriginalKey() throws Exception {
            String schema = converter.jsonToProto(
                  "{\"first-name\":\"a\",\"1st\":2,\"plain\":3,\"a b\":1,\"a_b\":2}");
            assertThat(schema).contains("string first_name = 1 [json_name = \"first-name\"];");
            assertThat(schema).contains("int32 _1st = 2 [json_name = \"1st\"];");
            assertThat(schema).contains("int32 plain = 3;");
            // Two keys that sanitise to the same name each keep their own.
            assertThat(schema).contains("[json_name = \"a b\"]").contains("[json_name = \"a_b\"]");
            // The schema still reads back.
            assertThat(convert(schema).get("Root").has("first-name")).isTrue();
        }

        @Test void absoluteReferencesBypassNestedShadows() throws Exception {
            JsonNode tree = convert("""
                  message Foo { string outer_value = 1; }
                  enum Kind { GLOBAL = 0; }
                  message Container {
                    message Foo { int32 inner_value = 1; }
                    enum Kind { LOCAL = 0; }
                    .Foo absolute = 1;
                    Foo relative = 2;
                    .Kind global_kind = 3;
                    Kind local_kind = 4;
                  }
                  """);
            assertThat(tree.at("/Container/absolute/outer_value").isTextual()).isTrue();
            assertThat(tree.at("/Container/relative/inner_value").isInt()).isTrue();
            assertThat(tree.at("/Container/global_kind").asText()).isEqualTo("GLOBAL");
            assertThat(tree.at("/Container/local_kind").asText()).isEqualTo("LOCAL");
        }

        @Test void packageReferencesResolveOnlyThroughDeclaredPackages() throws Exception {
            JsonNode tree = convert("""
                  syntax = "proto3";
                  package example.api;
                  message Foo { message Inner { string outer_value = 1; } }
                  message Container {
                    message Foo { message Inner { int32 inner_value = 1; } }
                    .example.api.Foo.Inner absolute = 1;
                    example.api.Foo.Inner qualified = 2;
                    api.Foo.Inner partial = 3;
                    Foo.Inner relative = 4;
                    .Foo.Inner wrong_absolute = 5;
                    unrelated.Foo.Inner wrong_package = 6;
                  }
                  """);
            for (String field : new String[]{"absolute", "qualified", "partial"})
                assertThat(tree.at("/Container/" + field + "/outer_value").isTextual()).isTrue();
            assertThat(tree.at("/Container/relative/inner_value").isInt()).isTrue();
            assertThat(tree.at("/Container/wrong_absolute").isEmpty()).isTrue();
            assertThat(tree.at("/Container/wrong_package").isEmpty()).isTrue();
        }

        @Test void aBoundFirstComponentDoesNotFallBackToAnotherScope() throws Exception {
            JsonNode tree = convert("""
                  message Foo { message Inner { string outer_value = 1; } }
                  message Container {
                    message Foo { int32 other = 1; }
                    Foo.Inner missing = 1;
                  }
                  """);
            assertThat(tree.at("/Container/missing").isEmpty()).isTrue();
        }


        @Test void generatedNumbersSkipTheReservedRangeAndReadBack() throws Exception {
            var input = json.createObjectNode();
            for (int i = 1; i <= 19_001; i++) input.put("field" + i, i);
            String schema = converter.jsonToProto(input.toString());
            assertThat(schema).contains("field18999 = 18999;", "field19000 = 20000;", "field19001 = 20001;");
            assertThat(schema).doesNotContain(" = 19000;", " = 19999;");
            assertThat(convert(schema).get("Root")).hasSize(19_001);
        }

        @Test void eachNestedMessageStartsItsOwnFieldNumbers() throws Exception {
            String schema = converter.jsonToProto("{\"first\":{\"value\":1},\"second\":{\"value\":2}}");
            assertThat(convert(schema).at("/Root/first/value").isInt()).isTrue();
            assertThat(convert(schema).at("/Root/second/value").isInt()).isTrue();
        }
    }

    @Nested @DisplayName("field options")
    class FieldOptions {

        private final ConversionPipeline pipeline = new ConversionPipeline();

        @Test @DisplayName("a field option no longer kills the whole message")
        void protoFieldOption() throws Exception {
            String json = pipeline.normalizeToJson(
                  "message A { string a = 1 [deprecated = true]; int32 b = 2; }",
                  Formats.FMT_PROTO, ConversionOptions.DEFAULTS);
            assertThat(json).contains("\"a\"").contains("\"b\"");
        }

        @Test @DisplayName("an option on an enum's zero value keeps it as the default")
        void protoEnumOption() throws Exception {
            String json = pipeline.normalizeToJson("""
                  enum Color { RED = 0 [deprecated = true]; GREEN = 1; }
                  message M { Color c = 1; }
                  """, Formats.FMT_PROTO, ConversionOptions.DEFAULTS);
            // Previously RED was skipped and GREEN silently became the default.
            assertThat(json).contains("RED").doesNotContain("GREEN");
        }

        @Test @DisplayName("repeated fields with options parse")
        void protoRepeatedOption() throws Exception {
            assertThat(pipeline.normalizeToJson(
                  "message A { repeated int32 xs = 1 [packed = true]; }",
                  Formats.FMT_PROTO, ConversionOptions.DEFAULTS)).contains("\"xs\"");
        }
    }

    /** Valid proto3 that the parser used to misread, drop or refuse. */
    @Nested @DisplayName("syntax the parser has to follow")
    class Syntax {

        private final ConversionPipeline pipeline = new ConversionPipeline();

        private JsonNode parse(String proto) throws Exception {
            return new ObjectMapper().readTree(
                  pipeline.normalizeToJson(proto, Formats.FMT_PROTO, ConversionOptions.DEFAULTS));
        }

        @Test @DisplayName("an option holding a list or message keeps its field, and its json_name")
        void optionsWithNestedBrackets() throws Exception {
            assertThat(parse("message M { string status = 1 [(buf.validate.field).string = {in: [\"a\", \"b\"]}];"
                  + " int32 code = 2; }").get("M"))
                  .isEqualTo(new ObjectMapper().readTree("{\"status\":\"\",\"code\":0}"));
            assertThat(parse("message M { string s = 1 [(v) = {in: [1, 2]; m: {x: [3]}}, json_name = \"st\"]; }")
                  .get("M").has("st")).isTrue();
            assertThatThrownBy(() -> parse("message M { string s = 1 [(v).string = {in: [\"a\"]}]; int32 c = 1; }"))
                  .hasMessageContaining("Duplicate field number 1");
        }

        @Test @DisplayName("map types without a space, and hex or octal field numbers")
        void numbersAndMaps() throws Exception {
            assertThat(parse("message M { map<string,string>labels = 1; }").get("M").get("labels").isObject()).isTrue();
            assertThat(parse("message M { string a = 0x10; string b = 017; }").get("M").size()).isEqualTo(2);
            // 010 is octal 8, so it clashes with 8.
            assertThatThrownBy(() -> parse("message M { string a = 010; string b = 8; }"))
                  .hasMessageContaining("Duplicate field number");
            assertThatThrownBy(() -> parse("message M { string a = 09; }")).hasMessageContaining("octal");
            assertThatThrownBy(() -> parse("message M { string a = 0x4A38; }"))   // 19000
                  .hasMessageContaining("reserved");
        }

        @Test @DisplayName("an enum's zero written in hex is still its default")
        void hexEnumValues() throws Exception {
            assertThat(parse("enum E { ZERO = 0x0; ONE = 0x1; }\nmessage M { E e = 1; }").get("M").get("e").asText())
                  .isEqualTo("ZERO");
        }

        @Test @DisplayName("an extend block inside a message declares fields of another message")
        void extendBlocksAreNotFields() throws Exception {
            assertThat(parse("message M { extend google.protobuf.MessageOptions { string my_opt = 50001; }"
                  + " string a = 1; }").get("M"))
                  .isEqualTo(new ObjectMapper().readTree("{\"a\":\"\"}"));
        }

        @Test @DisplayName("json_name reads surrogate-pair escapes and \\? as protoc does")
        void stringEscapes() throws Exception {
            assertThat(parse("message M { string v = 1 [json_name = \"\\uD83D\\uDE00-\\?\"]; }").get("M").has(
                  "\uD83D\uDE00-?")).isTrue();
            assertThatThrownBy(() -> parse("message M { string v = 1 [json_name = \"\\uD83D\"]; }"))
                  .hasMessageContaining("json_name");
        }

        @Test @DisplayName("a schema that multiplies at every level is refused before it exhausts memory")
        void expansionIsBounded() throws Exception {
            StringBuilder chain = new StringBuilder();
            for (int i = 0; i < 24; i++)
                chain.append("message L").append(i).append(" { L").append(i + 1).append(" a = 1; L")
                      .append(i + 1).append(" b = 2; }\n");
            chain.append("message L24 { string leaf = 1; }\n");
            assertThatThrownBy(() -> parse(chain.toString())).hasMessageContaining("expands to more than");
            // A few levels of the same shape are ordinary.
            assertThat(parse("message A { B x = 1; B y = 2; }\nmessage B { C x = 1; C y = 2; }\nmessage C { int32 v = 1; }")
                  .get("A").get("y").get("x").get("v").asInt()).isZero();
        }

        @Test @DisplayName("a schema nested deeper than conversion reads is refused, not a stack overflow")
        void nestingIsBounded() throws Exception {
            // 5,000 nested messages overflowed a worker's stack; 50,000 exhausted a 2 GB heap.
            assertThatThrownBy(() -> parse(nested(ProtoConverter.MAX_NESTING_DEPTH + 1)))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1,000 levels");
            // As deep as allowed still converts, on the 1 MB stack a pooled worker has.
            java.util.concurrent.atomic.AtomicReference<Object> result = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(null, () -> {
                try {
                    result.set(parse(nested(ProtoConverter.MAX_NESTING_DEPTH)));
                } catch (Throwable failure) {
                    result.set(failure);
                }
            }, "proto-depth", 1024 * 1024);
            worker.start();
            worker.join();
            assertThat(result.get()).isInstanceOf(JsonNode.class);
        }

        private static String nested(int depth) {
            StringBuilder proto = new StringBuilder("syntax = \"proto3\";\n");
            for (int i = 0; i < depth; i++) proto.append("message M").append(i).append(" {\n");
            return proto.append("int32 x = 1;\n").append("}\n".repeat(depth)).toString();
        }
    }

    /** Generated schemas have to get past protoc, old and new. */
    @Nested @DisplayName("field and JSON names protoc accepts")
    class ProtocNames {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
              "{\"user_id\":1,\"userId\":2}", "{\"name\":\"a\",\"Name\":\"b\"}", "{\"foo\":1,\"foo_\":2}",
              "{\"a-b\":1,\"aB\":2}", "{\"user\":{\"a\":1},\"User\":{\"b\":\"x\"}}",
              "{\"x_y\":1,\"xY\":2,\"x-y\":3,\"XY\":4,\"x y\":5}", "{\"id\":1,\"ID\":2,\"Id\":{\"iD\":[1]}}"})
        void namesAreUniqueTheWayProtocChecksThem(String json) throws Exception {
            String schema = converter.jsonToProto(json);
            assertProtocAcceptsNames(schema);
            // Every key still comes back as itself.
            JsonNode original = new ObjectMapper().readTree(json);
            JsonNode back = new ObjectMapper().readTree(converter.protoToJson(schema)).get("Root");
            java.util.List<String> keys = new java.util.ArrayList<>();
            original.fieldNames().forEachRemaining(keys::add);
            java.util.List<String> backKeys = new java.util.ArrayList<>();
            back.fieldNames().forEachRemaining(backKeys::add);
            assertThat(backKeys).isEqualTo(keys);
        }

        /**
         * protobuf 3.x: field names equal once lower-cased without underscores are
         * refused. 22 and later: default JSON names must be unique, and so must the
         * names in effect once custom json_names are applied.
         */
        private void assertProtocAcceptsNames(String schema) {
            java.util.Deque<java.util.List<String[]>> scopes = new java.util.ArrayDeque<>();
            java.util.regex.Pattern field = java.util.regex.Pattern.compile(
                  "^\\s*(?:repeated\\s+)?\\S+\\s+(\\w+)\\s*=\\s*\\d+(?:\\s*\\[json_name = \"((?:[^\"\\\\]|\\\\.)*)\"])?;$");
            for (String line : schema.split("\n")) {
                if (line.trim().startsWith("message ")) { scopes.push(new java.util.ArrayList<>()); continue; }
                if (line.trim().equals("}")) { check(scopes.pop(), schema); continue; }
                java.util.regex.Matcher m = field.matcher(line);
                if (m.matches()) scopes.peek().add(new String[]{m.group(1), m.group(2)});
            }
        }

        private void check(java.util.List<String[]> fields, String schema) {
            java.util.Set<String> legacy = new java.util.HashSet<>(), defaults = new java.util.HashSet<>(),
                  effective = new java.util.HashSet<>();
            for (String[] f : fields) {
                String defaultName = ProtoConverter.toJsonName(f[0]);
                assertThat(legacy.add(f[0].replace("_", "").toLowerCase(java.util.Locale.ROOT)))
                      .as("3.x name clash on %s in%n%s", f[0], schema).isTrue();
                assertThat(defaults.add(defaultName)).as("default JSON name of %s in%n%s", f[0], schema).isTrue();
                assertThat(effective.add(f[1] != null ? f[1] : defaultName))
                      .as("JSON name of %s in%n%s", f[0], schema).isTrue();
            }
        }
    }

    @Nested @DisplayName("proto2 and editions constructs")
    class Proto2Constructs {
        private final ProtoConverter converter = new ProtoConverter();

        @Test @DisplayName("extensions ranges are declarations, not fields")
        void extensionRanges() throws Exception {
            assertThat(converter.protoToJson("syntax = \"proto2\";\nmessage FeedMessage {\n"
                  + "  optional int32 header = 1;\n  extensions 1000 to 1999;\n  extensions 5000 to max;\n}\n"))
                  .isEqualTo("{\"FeedMessage\":{\"header\":0}}");
            assertThat(converter.protoToJson("edition = \"2023\";\nmessage M {\n  int32 a = 1;\n"
                  + "  extensions 100 to 199 [verification = UNVERIFIED];\n}\n"))
                  .isEqualTo("{\"M\":{\"a\":0}}");
        }

        @Test @DisplayName("an aggregate option may separate its fields with semicolons")
        void aggregateOptions() throws Exception {
            assertThat(converter.protoToJson("syntax = \"proto3\";\nmessage Book {\n"
                  + "  option (google.api.resource) = {\n    type: \"library.googleapis.com/Book\";\n"
                  + "    pattern: \"shelves/{shelf}/books/{book}\";\n  };\n  string name = 1;\n"
                  + "  oneof kind {\n    option (my.opt) = { a: 1; b: [2, 3] };\n    string isbn = 2;\n  }\n}\n"))
                  .isEqualTo("{\"Book\":{\"name\":\"\",\"isbn\":\"\"}}");
        }

        @Test @DisplayName("a group is a message and a field named after it, numbered in its parent")
        void groups() throws Exception {
            assertThat(converter.protoToJson("syntax = \"proto2\";\nmessage SearchResponse {\n"
                  + "  repeated group Result = 1 {\n    required string url = 2;\n    optional string title = 3;\n  }\n"
                  + "  optional int32 total = 4;\n}\n"))
                  .isEqualTo("{\"SearchResponse\":{\"result\":[],\"total\":0}}");
            // The group's fields are numbered apart from its parent's.
            assertThat(converter.protoToJson("syntax = \"proto2\";\nmessage M {\n  optional int32 x = 2;\n"
                  + "  optional group G = 1 {\n    optional int32 a = 1;\n    optional int32 b = 2;\n  }\n}\n"))
                  .isEqualTo("{\"M\":{\"x\":0,\"g\":{\"a\":0,\"b\":0}}}");
            // Its own number still counts in its parent.
            assertThatThrownBy(() -> converter.protoToJson("syntax = \"proto2\";\nmessage M {\n  optional int32 x = 1;\n"
                  + "  optional group G = 1 {\n    optional int32 a = 1;\n  }\n}\n"))
                  .hasMessageContaining("Duplicate field number 1");
        }

        @Test @DisplayName("proto2 defaults are the values the fields start with")
        void explicitDefaults() throws Exception {
            assertThat(converter.protoToJson("syntax = \"proto2\";\nmessage SearchRequest {\n"
                  + "  enum Corpus { UNIVERSAL = 0; WEB = 1; }\n"
                  + "  optional int32 result_per_page = 3 [default = 10];\n"
                  + "  optional Corpus corpus = 4 [default = WEB];\n"
                  + "  optional string lang = 5 [default = \"en\"];\n"
                  + "  optional bool safe = 6 [default = true];\n"
                  + "  optional double ratio = 7 [default = -inf];\n"
                  + "  optional sint64 offset = 8 [default = -0x10];\n"
                  + "  optional bytes magic = 9 [default = \"\\x01\\x02\"];\n}\n"))
                  .isEqualTo("{\"SearchRequest\":{\"result_per_page\":10,\"corpus\":\"WEB\",\"lang\":\"en\","
                        + "\"safe\":true,\"ratio\":\"-Infinity\",\"offset\":-16,\"magic\":\"AQI=\"}}");
        }
    }

    @Nested @DisplayName("Schemas that read their own JSON")
    class SelfReadingSchemas {
        private final ProtoConverter converter = new ProtoConverter();

        @Test @DisplayName("values of mixed kinds, and nulls in a list, are google.protobuf.Value")
        void structValues() throws Exception {
            String proto = converter.jsonToProto("{\"values\":[1,\"two\",true],\"a\":[1,null,3],"
                  + "\"rows\":[{\"v\":1},{\"v\":\"x\"}],\"n\":[1,2]}");
            assertThat(proto).startsWith("syntax = \"proto3\";\n\nimport \"google/protobuf/struct.proto\";\n\n")
                  .contains("repeated google.protobuf.Value values = 1;")
                  .contains("repeated google.protobuf.Value a = 2;")
                  .contains("google.protobuf.Value v = 1;")
                  .contains("repeated int32 n = 4;");
            assertThat(converter.jsonToProto("{\"a\":1}")).doesNotContain("import");
        }

        @Test @DisplayName("a key protoc cannot take as a JSON name is refused, not emitted")
        void bracketedKeys() {
            assertThatThrownBy(() -> converter.jsonToProto("{\"[id]\":1}"))
                  .hasMessageContaining("[id]").hasMessageContaining("square brackets");
        }
    }
}
