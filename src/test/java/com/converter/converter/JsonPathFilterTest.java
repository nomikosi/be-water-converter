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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Subtree filter")
class JsonPathFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode tree() throws Exception {
        return mapper.readTree("""
              {"users":[{"name":"Ada","tags":["x","y"]},{"name":"Grace"}],
               "meta":{"count":2},
               "odd.key":{"a":1},
               "with/slash":1}
              """);
    }

    @Test @DisplayName("dotted and pointer syntax select the same node")
    void equivalentSyntaxes() throws Exception {
        assertThat(JsonPathFilter.apply(tree(), "users[0].name").asText()).isEqualTo("Ada");
        assertThat(JsonPathFilter.apply(tree(), "/users/0/name").asText()).isEqualTo("Ada");
    }

    @Test @DisplayName("a JSONPath-style $ root prefix is accepted")
    void dollarPrefix() throws Exception {
        assertThat(JsonPathFilter.apply(tree(), "$.meta.count").asInt()).isEqualTo(2);
        assertThat(JsonPathFilter.apply(tree(), "$['meta']['count']").asInt()).isEqualTo(2);
    }

    @Test @DisplayName("an empty path selects the whole document")
    void emptyPath() throws Exception {
        assertThat(JsonPathFilter.apply(tree(), "").has("users")).isTrue();
        assertThat(JsonPathFilter.apply(tree(), "   ").has("users")).isTrue();
    }

    @Test @DisplayName("selecting a container keeps its structure")
    void selectContainer() throws Exception {
        JsonNode users = JsonPathFilter.apply(tree(), "users");
        assertThat(users.isArray()).isTrue();
        assertThat(users).hasSize(2);
    }

    @Test @DisplayName("keys containing dots or slashes are reachable via brackets")
    void awkwardKeys() throws Exception {
        // A dotted path cannot express these, which is why bracket-quoting exists.
        assertThat(JsonPathFilter.apply(tree(), "['odd.key'].a").asInt()).isEqualTo(1);
        // '/' must be escaped as ~1 in a pointer; the bracket form does it for you.
        assertThat(JsonPathFilter.apply(tree(), "['with/slash']").asInt()).isEqualTo(1);
        assertThat(JsonPathFilter.apply(tree(), "/with~1slash").asInt()).isEqualTo(1);
    }

    @Test @DisplayName("a path matching nothing is an error, not a silent null")
    void noMatchThrows() throws Exception {
        assertThatThrownBy(() -> JsonPathFilter.apply(tree(), "users[5].name"))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("matched nothing");
        assertThatThrownBy(() -> JsonPathFilter.apply(tree(), "nope"))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("malformed paths are rejected with a usable message")
    void malformed() {
        assertThatThrownBy(() -> JsonPathFilter.toPointer("users[0"))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Unclosed");
        assertThatThrownBy(() -> JsonPathFilter.toPointer("users[]"))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Empty");
    }

    @Test @DisplayName("the filter narrows a real conversion")
    void filterThroughPipeline() throws Exception {
        ConversionPipeline pipeline = new ConversionPipeline();
        String input = "{\"users\":[{\"name\":\"Ada\"},{\"name\":\"Grace\"}],\"meta\":{\"count\":2}}";
        String pivot = pipeline.normalizeToJson(input, ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS.withFilterPath("users"));
        assertThat(pivot).contains("Ada").contains("Grace").doesNotContain("count");

        // And the narrowed pivot renders as CSV of just those rows.
        String csv = pipeline.renderFromJson(pivot, ConversionPipeline.FMT_CSV,
              ConversionOptions.DEFAULTS);
        assertThat(csv).contains("name").contains("Ada").contains("Grace");
    }

    @Test @DisplayName("no filter leaves the document whole")
    void noFilterByDefault() throws Exception {
        ConversionPipeline pipeline = new ConversionPipeline();
        String pivot = pipeline.normalizeToJson("{\"a\":1,\"b\":2}",
              ConversionPipeline.FMT_JSON, ConversionOptions.DEFAULTS);
        assertThat(pivot).contains("\"a\"").contains("\"b\"");
    }
}
