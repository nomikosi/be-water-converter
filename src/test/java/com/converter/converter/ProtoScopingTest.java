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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Type names resolve the way protoc resolves them: a message's own nested
 * types first, then its enclosing messages', then the file's top level. One
 * flat registry keyed by simple name gave every same-named nested type the
 * last definition registered, and could not follow a dotted name at all.
 */
@DisplayName("Protobuf type scoping")
class ProtoScopingTest {

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
        assertThat(tree.at("/B/p/x").isInt()).isTrue();
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
        assertThat(converter.protoToJson(schema)).contains("first_name");
    }
}
