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
 * Tests for the improved validation messages on malformed Protobuf input.
 */
@DisplayName("ProtoConverter – Validation Messages")
class ProtoConverterValidationTest {

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
