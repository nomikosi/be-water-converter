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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** YAML conversions must not quietly change a value's type or drop one. */
@DisplayName("YAML fidelity")
class YamlFidelityTest {

    private final JsonYamlConverter converter = new JsonYamlConverter();

    @Test @DisplayName("a plainly numeric string stays a string across the round trip")
    void numericStringsKeepTheirType() throws Exception {
        String json = "{\"id\":\"123\",\"price\":\"9.99\",\"neg\":\"-5\"}";
        assertThat(converter.jsonToYaml(json)).contains("\"123\"").contains("\"9.99\"");
        assertThat(converter.yamlToJson(converter.jsonToYaml(json)))
              .contains("\"id\":\"123\"")
              .contains("\"price\":\"9.99\"")
              .contains("\"neg\":\"-5\"");
        // Ordinary text is still emitted bare — that is the point of the setting.
        assertThat(converter.jsonToYaml("{\"name\":\"Alice\"}")).contains("name: Alice");
    }

    @Test @DisplayName("KNOWN LIMIT: strings YAML resolves by a non-decimal rule still retype")
    void nonDecimalLookalikesStillRetype() throws Exception {
        // Pinned so the gap is visible rather than discovered. Jackson's
        // ALWAYS_QUOTE_NUMBERS_AS_STRINGS quotes plain decimals only, so these
        // go out bare and YAML reads them back as something else. Closing it
        // needs quoting every string, which costs the readable output the
        // JsonYamlConverter tests deliberately pin.
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"0x1F\"}")))
              .isEqualTo("{\"a\":31}");
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"1e3\"}")))
              .isEqualTo("{\"a\":1000.0}");
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"12:30:00\"}")))
              .isEqualTo("{\"a\":45000}");
    }

    @Test @DisplayName("keys that differ in YAML but collide as JSON are refused")
    void collidingKeysAreRefused() {
        // valueToTree stringifies the key, so the second silently won.
        assertThatThrownBy(() -> converter.yamlToJson("1: a\n\"1\": b\n"))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("identical as JSON keys");
        assertThatThrownBy(() -> converter.yamlToJson("true: a\n\"true\": b\n"))
              .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.yamlToJson("outer:\n  1: a\n  \"1\": b\n"))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("distinct non-string keys still convert")
    void nonStringKeysStillWork() throws Exception {
        assertThat(converter.yamlToJson("1: a\n2: b\n")).isEqualTo("{\"1\":\"a\",\"2\":\"b\"}");
    }

    @Test @DisplayName("a duplicate mapping key is an error, not a silent last-wins")
    void duplicateKeysAreRefused() {
        assertThatThrownBy(() -> converter.yamlToJson("a: 1\na: 2\n"))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("an interior empty document keeps its place in the stream")
    void interiorEmptyDocumentsArePreserved() throws Exception {
        // Dropping them renumbered every later document.
        assertThat(converter.yamlToJson("--- null\n--- {a: 1}\n")).isEqualTo("[null,{\"a\":1}]");
        assertThat(converter.yamlToJson("--- {a: 1}\n---\n--- {b: 2}\n"))
              .isEqualTo("[{\"a\":1},null,{\"b\":2}]");
        // A trailing separator terminates the last document rather than starting one.
        assertThat(converter.yamlToJson("name: Alice\n---\n")).isEqualTo("{\"name\":\"Alice\"}");
        assertThatThrownBy(() -> converter.yamlToJson("---\n---\n"))
              .isInstanceOf(IllegalArgumentException.class);
    }
}
