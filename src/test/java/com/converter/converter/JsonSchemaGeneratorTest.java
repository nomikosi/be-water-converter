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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonSchemaGenerator")
class JsonSchemaGeneratorTest {

    private JsonSchemaGenerator generator;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setUp() { generator = new JsonSchemaGenerator(); }

    private JsonNode schemaOf(String json) throws Exception {
        return mapper.readTree(generator.fromJson(json));
    }

    @Test @DisplayName("declares the 2020-12 dialect at the root only")
    void dialectAtRootOnly() throws Exception {
        JsonNode schema = schemaOf("{\"nested\":{\"a\":1}}");
        assertThat(schema.get("$schema").asText()).isEqualTo(JsonSchemaGenerator.SCHEMA_DIALECT);
        assertThat(schema.at("/properties/nested/$schema").isMissingNode()).isTrue();
    }

    @Test @DisplayName("maps scalars to JSON Schema types")
    void scalarTypes() throws Exception {
        JsonNode s = schemaOf("{\"s\":\"x\",\"i\":1,\"d\":1.5,\"b\":true,\"n\":null}");
        assertThat(s.at("/properties/s/type").asText()).isEqualTo("string");
        assertThat(s.at("/properties/i/type").asText()).isEqualTo("integer");
        assertThat(s.at("/properties/d/type").asText()).isEqualTo("number");
        assertThat(s.at("/properties/b/type").asText()).isEqualTo("boolean");
        assertThat(s.at("/properties/n/type").asText()).isEqualTo("null");
    }

    @Test @DisplayName("every observed key is required by default")
    void requiredByDefault() throws Exception {
        JsonNode s = schemaOf("{\"a\":1,\"b\":2}");
        assertThat(s.get("required")).isNotNull();
        assertThat(s.get("required").toString()).isEqualTo("[\"a\",\"b\"]");
    }

    @Test @DisplayName("requireAllKeys=false omits required")
    void requiredCanBeDisabled() throws Exception {
        JsonNode s = mapper.readTree(generator.fromJson("{\"a\":1}", false));
        assertThat(s.get("required")).isNull();
        assertThat(s.at("/properties/a/type").asText()).isEqualTo("integer");
    }

    @Test @DisplayName("uniform arrays collapse to a single items subschema")
    void uniformArray() throws Exception {
        JsonNode s = schemaOf("{\"xs\":[1,2,3]}");
        assertThat(s.at("/properties/xs/type").asText()).isEqualTo("array");
        assertThat(s.at("/properties/xs/items/type").asText()).isEqualTo("integer");
        assertThat(s.at("/properties/xs/items/anyOf").isMissingNode()).isTrue();
    }

    @Test @DisplayName("mixed arrays produce anyOf rather than taking element 0")
    void mixedArrayProducesAnyOf() throws Exception {
        JsonNode s = schemaOf("{\"xs\":[1,\"a\"]}");
        JsonNode anyOf = s.at("/properties/xs/items/anyOf");
        assertThat(anyOf.isArray()).isTrue();
        assertThat(anyOf).hasSize(2);
        assertThat(anyOf.get(0).get("type").asText()).isEqualTo("integer");
        assertThat(anyOf.get(1).get("type").asText()).isEqualTo("string");
    }

    @Test @DisplayName("empty array constrains nothing about items")
    void emptyArray() throws Exception {
        JsonNode s = schemaOf("{\"xs\":[]}");
        assertThat(s.at("/properties/xs/type").asText()).isEqualTo("array");
        assertThat(s.at("/properties/xs/items").isMissingNode()).isTrue();
    }

    @Test @DisplayName("nested objects recurse")
    void nestedObjects() throws Exception {
        JsonNode s = schemaOf("{\"user\":{\"id\":1,\"tags\":[\"a\"]}}");
        assertThat(s.at("/properties/user/type").asText()).isEqualTo("object");
        assertThat(s.at("/properties/user/properties/id/type").asText()).isEqualTo("integer");
        assertThat(s.at("/properties/user/properties/tags/items/type").asText()).isEqualTo("string");
    }

    @Test @DisplayName("a root array is described as an array")
    void rootArray() throws Exception {
        JsonNode s = schemaOf("[{\"a\":1},{\"a\":2}]");
        assertThat(s.get("type").asText()).isEqualTo("array");
        // Both elements share a shape, so items must not become an anyOf.
        assertThat(s.at("/items/type").asText()).isEqualTo("object");
        assertThat(s.at("/items/anyOf").isMissingNode()).isTrue();
    }

    @Test @DisplayName("blank input is rejected")
    void blankRejected() {
        assertThatThrownBy(() -> generator.fromJson("  "))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("reachable as a pipeline output format")
    void viaPipeline() throws Exception {
        String out = new ConversionPipeline()
              .renderFromJson("{\"a\":1}", ConversionPipeline.FMT_SCHEMA,
                    ConversionOptions.DEFAULTS);
        assertThat(mapper.readTree(out).get("$schema").asText())
              .isEqualTo(JsonSchemaGenerator.SCHEMA_DIALECT);
    }
}
