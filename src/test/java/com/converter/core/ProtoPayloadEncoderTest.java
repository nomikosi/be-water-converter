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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** The payload writer's own rules; ProtoPayloadCrossCheckTest holds it to protobuf-java. */
@DisplayName("Writing Protobuf payloads")
class ProtoPayloadEncoderTest {

    private final ProtoPayloadEncoder encoder = new ProtoPayloadEncoder(new ProtoConverter());
    private final ProtoPayloadDecoder decoder = new ProtoPayloadDecoder(new ProtoConverter());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SCHEMA = """
          syntax = "proto3";
          package demo;
          import "google/protobuf/timestamp.proto";
          message P {
            string name = 1;
            int32 age = 2;
            repeated int32 xs = 3;
            map<string, int32> counts = 4;
            Kind kind = 5;
            bytes data = 6;
            optional int32 explicit = 7;
            oneof choice {
              string text = 8;
              int64 number = 9;
            }
            google.protobuf.Timestamp at = 10;
            map<int32, string> by_id = 11;
            sint32 signed = 12;
            enum Kind {
              NONE = 0;
              SOME = 1;
            }
          }
          """;

    private String hex(String json) throws Exception {
        return encoder.encode(json, SCHEMA, "demo.P", Map.of(), false);
    }

    @Test @DisplayName("fields are written in number order, a default left out unless the field has presence")
    void layout() throws Exception {
        assertThat(hex("{\"age\":36,\"name\":\"Ada\"}")).isEqualTo("0a034164611024");
        assertThat(hex("{\"name\":\"\",\"age\":0,\"kind\":\"NONE\"}")).isEmpty();
        // proto3 optional: set to its default, it is still written.
        assertThat(hex("{\"explicit\":0}")).isEqualTo("3800");
        // A oneof member too.
        assertThat(hex("{\"number\":0}")).isEqualTo("4800");
        assertThat(encoder.encode("{\"age\":36}", SCHEMA, "demo.P", Map.of(), true)).isEqualTo("ECQ=");
    }

    @Test @DisplayName("repeated numbers are packed; maps, enums, bytes and a Timestamp take their JSON forms")
    void values() throws Exception {
        assertThat(hex("{\"xs\":[1,2,300]}")).isEqualTo("1a040102ac02");
        assertThat(hex("{\"kind\":\"SOME\"}")).isEqualTo(hex("{\"kind\":1}")).isEqualTo("2801");
        assertThat(hex("{\"data\":\"AQI=\"}")).isEqualTo(hex("{\"data\":\"AQI\"}")).isEqualTo("32020102");
        assertThat(hex("{\"counts\":{\"a\":1}}")).isEqualTo("22050a0161" + "1001");
        assertThat(hex("{\"by_id\":{\"7\":\"x\"}}")).isEqualTo("5a050807" + "120178");
        assertThat(hex("{\"at\":\"1970-01-01T00:00:01.5Z\"}")).isEqualTo("52080801" + "1080cab5ee01");
        assertThat(hex("{\"signed\":-1}")).isEqualTo("6001");
        // Numbers may be strings, and a whole number a decimal.
        assertThat(hex("{\"age\":\"36\"}")).isEqualTo(hex("{\"age\":36.0}")).isEqualTo("1024");
        // A field by its JSON name, as protobuf's parser takes it.
        assertThat(hex("{\"byId\":{\"7\":\"x\"}}")).isEqualTo(hex("{\"by_id\":{\"7\":\"x\"}}"));
        // Null is a field not set.
        assertThat(hex("{\"name\":null,\"at\":null}")).isEmpty();
    }

    @Test @DisplayName("what the decoder writes, the encoder writes back as the same payload")
    void roundTrip() throws Exception {
        String payload = hex("{\"name\":\"Ada\",\"xs\":[1,-2],\"counts\":{\"a\":3},\"kind\":\"SOME\",\"data\":\"AQI=\","
              + "\"text\":\"t\",\"at\":\"2024-05-01T10:00:00Z\",\"by_id\":{\"-5\":\"neg\"},\"signed\":-70}");
        String decoded = decoder.decode(payload, SCHEMA, "demo.P");
        assertThat(hex(decoded)).isEqualTo(payload);
        assertThat(JSON.readTree(decoder.decode(hex(decoded), SCHEMA, "demo.P"))).isEqualTo(JSON.readTree(decoded));
    }

    @Test @DisplayName("what no field holds, or no value of its type, is refused with its path")
    void refusals() {
        assertThatThrownBy(() -> hex("{\"nickname\":\"x\"}"))
              .hasMessage("$ has the key \"nickname\", which the message demo.P has no field for.");
        assertThatThrownBy(() -> hex("{\"text\":\"a\",\"number\":1}"))
              .hasMessageContaining("sets both text and number, members of the oneof choice");
        assertThatThrownBy(() -> hex("{\"age\":2147483648}")).hasMessageContaining("$.age is 2147483648, outside the range");
        assertThatThrownBy(() -> hex("{\"age\":1.5}")).hasMessageContaining("$.age is 1.5, where an int32 is a whole number");
        assertThatThrownBy(() -> hex("{\"kind\":\"MANY\"}")).hasMessageContaining("which the enum demo.P.Kind has no value named");
        assertThatThrownBy(() -> hex("{\"xs\":[1,null]}")).hasMessage("$.xs[1] is null, which a list cannot hold.");
        assertThatThrownBy(() -> hex("{\"counts\":{\"a\":\"x\"}}")).hasMessageContaining("$.counts[\"a\"] is \"x\"");
        assertThatThrownBy(() -> hex("{\"at\":\"yesterday\"}")).hasMessageContaining("$.at is \"yesterday\", where a Timestamp");
        assertThatThrownBy(() -> hex("{\"age\":1,\"age\":2}")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> hex("{\"by_id\":{\"x\":\"y\"}}")).hasMessageContaining("where an int32 is a whole number");
        assertThatThrownBy(() -> hex("[1]")).hasMessage("$ is a list, where the message demo.P takes an object.");
        assertThatThrownBy(() -> encoder.encode("{}", "", "", Map.of(), false))
              .hasMessageContaining("takes its schema and message type");
        assertThatThrownBy(() -> encoder.encode("{}", SCHEMA, "demo.Q", Map.of(), false))
              .hasMessageContaining("The schema declares no message demo.Q");
    }

    @Test @DisplayName("a field of a type in no file at hand cannot be written")
    void unresolved() {
        String schema = "syntax = \"proto3\";\nimport \"other.proto\";\nmessage R { other.Thing thing = 1; }\n";
        assertThatThrownBy(() -> encoder.encode("{\"thing\":{}}", schema, "R", Map.of(), false))
              .hasMessage("$.thing is of the type other.Thing, which is in no file of the schema at hand.");
    }
}
