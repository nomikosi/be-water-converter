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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProtoJsonNameTest {
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
