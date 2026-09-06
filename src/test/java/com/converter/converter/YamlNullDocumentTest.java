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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class YamlNullDocumentTest {
    private final JsonYamlConverter converter = new JsonYamlConverter();

    @ParameterizedTest
    @ValueSource(strings = {"null", "~", "Null", "NULL", "--- null\n", "null # explicit\n",
          "!!null ''", "--- &empty null\n"})
    void preservesExplicitNullScalars(String yaml) throws Exception {
        assertThat(converter.yamlToJson(yaml)).isEqualTo("null");
        assertThat(converter.yamlToJson(converter.formatPreservingDocuments(yaml, false)))
              .isEqualTo("null");
    }

    @Test void acceptsTheWritersOwnNullOutput() throws Exception {
        assertThat(converter.yamlToJson(converter.jsonToYaml("null"))).isEqualTo("null");
    }

    @Test void preservesMultipleExplicitNullDocuments() throws Exception {
        String yaml = "--- null\n--- ~\n--- !!null ''\n";
        assertThat(converter.yamlToJson(yaml)).isEqualTo("[null,null,null]");
        assertThat(converter.yamlToJson(converter.formatPreservingDocuments(yaml, true)))
              .isEqualTo("[null,null,null]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"# comment only\n", "---\n", "---\n...\n", "---\n---\n"})
    void stillRejectsStreamsContainingOnlyEmptyDocuments(String yaml) {
        assertThatThrownBy(() -> converter.yamlToJson(yaml))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no documents");
    }
}
