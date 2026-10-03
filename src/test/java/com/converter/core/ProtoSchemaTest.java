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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A schema read to decode payloads against")
class ProtoSchemaTest {

    private static final String SCHEMA = """
          syntax = "proto3";
          package shop.v1;

          import "google/protobuf/timestamp.proto";

          message Order {
            string id = 1 [json_name = "orderId"];
            repeated Line lines = 2;
            map<string, Status> states = 3;
            optional int64 total = 4;
            oneof paid {
              Card card = 5;
              string voucher = 6;
            }
            google.protobuf.Timestamp placed = 7;
            .shop.v1.Status status = 8;

            message Line {
              string sku = 1;
              uint32 count = 2;
            }
          }

          message Card {
            bytes token = 1;
          }

          enum Status {
            STATUS_UNSPECIFIED = 0;
            PLACED = 1;
          }
          """;

    private final ProtoSchema schema = new ProtoConverter().readSchema(SCHEMA);

    private ProtoSchema.Field field(String message, int number) {
        return schema.messages().get(message).fields().get(number);
    }

    @Test @DisplayName("message types by full name, in the order they are declared, nested ones after their parent")
    void messageNames() {
        assertThat(schema.messageNames()).containsExactly("shop.v1.Order", "shop.v1.Order.Line", "shop.v1.Card");
        assertThat(schema.enums()).containsOnlyKeys("shop.v1.Status");
        assertThat(schema.enums().get("shop.v1.Status").names()).isEqualTo(Map.of(0, "STATUS_UNSPECIFIED", 1, "PLACED"));
    }

    @Test @DisplayName("each field's kind and type, resolved as protoc resolves them")
    void fields() {
        ProtoSchema.Field id = field("shop.v1.Order", 1);
        assertThat(id.name()).isEqualTo("id");
        assertThat(id.jsonKey()).isEqualTo("orderId");
        assertThat(id.kind()).isEqualTo(ProtoSchema.Kind.SCALAR);
        assertThat(id.type()).isEqualTo("string");

        ProtoSchema.Field lines = field("shop.v1.Order", 2);
        assertThat(lines.repeated()).isTrue();
        assertThat(lines.kind()).isEqualTo(ProtoSchema.Kind.MESSAGE);
        assertThat(lines.type()).isEqualTo("shop.v1.Order.Line");

        ProtoSchema.Field states = field("shop.v1.Order", 3);
        assertThat(states.kind()).isEqualTo(ProtoSchema.Kind.MAP);
        assertThat(states.mapKey()).isEqualTo("string");
        assertThat(states.mapValue().kind()).isEqualTo(ProtoSchema.Kind.ENUM);
        assertThat(states.mapValue().type()).isEqualTo("shop.v1.Status");

        // From a file this one imports: decoded raw.
        ProtoSchema.Field placed = field("shop.v1.Order", 7);
        assertThat(placed.kind()).isEqualTo(ProtoSchema.Kind.UNRESOLVED);
        assertThat(placed.type()).isEqualTo("google.protobuf.Timestamp");

        assertThat(field("shop.v1.Order", 8).type()).isEqualTo("shop.v1.Status");
        assertThat(field("shop.v1.Order", 5).type()).isEqualTo("shop.v1.Card");
    }

    @Test @DisplayName("oneof members know their oneof; presence is implicit only where proto3 makes it so")
    void oneofsAndPresence() {
        assertThat(field("shop.v1.Order", 5).oneof()).isEqualTo("paid");
        assertThat(field("shop.v1.Order", 6).oneof()).isEqualTo("paid");
        assertThat(field("shop.v1.Order", 1).oneof()).isNull();

        assertThat(field("shop.v1.Order", 1).implicitPresence()).as("a plain string").isTrue();
        assertThat(field("shop.v1.Order", 8).implicitPresence()).as("a plain enum").isTrue();
        assertThat(field("shop.v1.Order", 4).implicitPresence()).as("marked optional").isFalse();
        assertThat(field("shop.v1.Order", 6).implicitPresence()).as("in a oneof").isFalse();
        assertThat(field("shop.v1.Order", 2).implicitPresence()).as("repeated").isFalse();
        assertThat(field("shop.v1.Card", 1).implicitPresence()).as("plain bytes").isTrue();

        ProtoSchema proto2 = new ProtoConverter().readSchema(SCHEMA.replace("\"proto3\"", "\"proto2\""));
        assertThat(proto2.messages().get("shop.v1.Card").fields().get(1).implicitPresence()).isFalse();
    }

    @Test @DisplayName("a schema without messages, or without text, is refused")
    void refusals() {
        assertThatThrownBy(() -> new ProtoConverter().readSchema("  ")).hasMessageContaining("The schema is empty");
        assertThatThrownBy(() -> new ProtoConverter().readSchema("syntax = \"proto3\";"))
              .hasMessageContaining("No 'message' blocks found");
    }
}
