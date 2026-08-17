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

    @Test @DisplayName("times, exponents and dates now survive the round trip too")
    void formerLookalikesNowSurvive() throws Exception {
        // These were sexagesimal, float and timestamp under YAML 1.1.
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"12:30:00\"}")))
              .isEqualTo("{\"a\":\"12:30:00\"}");
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"2024-01-01\"}")))
              .isEqualTo("{\"a\":\"2024-01-01\"}");
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"0777\"}")))
              .isEqualTo("{\"a\":\"0777\"}");
    }

    @Test @DisplayName("KNOWN LIMIT: hex- and exponent-looking strings come back as numbers")
    void numberLookalikesStillRetype() throws Exception {
        // Both are genuine numbers in YAML 1.2, so READING them as numbers is
        // right. What is wrong is the OUTPUT side: MINIMIZE_QUOTES emits the
        // strings bare, and ALWAYS_QUOTE_NUMBERS_AS_STRINGS covers plain
        // decimals only. Closing it needs quoting every string, which costs the
        // readable output the JsonYamlConverter tests deliberately pin.
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"0x1F\"}")))
              .isEqualTo("{\"a\":31}");
        assertThat(converter.yamlToJson(converter.jsonToYaml("{\"a\":\"1e3\"}")))
              .isEqualTo("{\"a\":1000.0}");
    }

    @Test @DisplayName("the resolver is exercised directly, not only through quoted output")
    void resolverReadsScalarsDirectly() throws Exception {
        // Round-tripping a JSON string emits it QUOTED, and a quoted scalar
        // never reaches implicit resolution — so those assertions passed whether
        // or not the resolver was right. These read the YAML as written.
        assertThat(converter.yamlToJson("a: 0777\n")).isEqualTo("{\"a\":\"0777\"}");
        assertThat(converter.yamlToJson("a: 010\n")).isEqualTo("{\"a\":\"010\"}");
        assertThat(converter.yamlToJson("a: 12:30:00\n")).isEqualTo("{\"a\":\"12:30:00\"}");
        assertThat(converter.yamlToJson("a: 2024-01-01\n")).isEqualTo("{\"a\":\"2024-01-01\"}");
        // 0o777 is YAML 1.2 octal that SnakeYAML's constructor cannot build, so
        // it stays text rather than throwing NumberFormatException.
        assertThat(converter.yamlToJson("a: 0o777\n")).isEqualTo("{\"a\":\"0o777\"}");
        // Still numbers, per YAML 1.2.
        assertThat(converter.yamlToJson("a: 1e3\n")).isEqualTo("{\"a\":1000.0}");
        assertThat(converter.yamlToJson("a: .5e3\n")).isEqualTo("{\"a\":500.0}");
        assertThat(converter.yamlToJson("a: 0x1F\n")).isEqualTo("{\"a\":31}");
        assertThat(converter.yamlToJson("a: 0\n")).isEqualTo("{\"a\":0}");
        assertThat(converter.yamlToJson("a: 42\n")).isEqualTo("{\"a\":42}");
    }

    @Test @DisplayName("a mapping inside a sequence is checked for colliding keys too")
    void collidingKeysInsideSequences() throws Exception {
        // The guard only descended through Map values, so a list of mappings —
        // every Kubernetes containers: block — was never examined.
        assertThatThrownBy(() -> converter.yamlToJson("items:\n  - 1: a\n    \"1\": b\n"))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("identical as JSON keys");
        // Nested a second level down, to prove the descent is not one-deep.
        assertThatThrownBy(() -> converter.yamlToJson(
              "outer:\n  - inner:\n      - 1: a\n        \"1\": b\n"))
              .isInstanceOf(IllegalArgumentException.class);
        // An ordinary list of mappings still converts.
        assertThat(converter.yamlToJson("items:\n  - a: 1\n  - b: 2\n"))
              .isEqualTo("{\"items\":[{\"a\":1},{\"b\":2}]}");
    }

    @Test @DisplayName("a document written as null is kept; a bare trailing separator is not")
    void explicitNullDocumentIsKept() throws Exception {
        // Popping every trailing null took the user's own null document with it.
        assertThat(converter.yamlToJson("--- {a: 1}\n--- null\n"))
              .isEqualTo("[{\"a\":1},null]");
        assertThat(converter.yamlToJson("--- {a: 1}\n--- null\n--- null\n"))
              .isEqualTo("[{\"a\":1},null,null]");
        // A separator with nothing after it still closes the last document.
        assertThat(converter.yamlToJson("name: Alice\n---\n")).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(converter.yamlToJson("--- {a: 1}\n---\n")).isEqualTo("{\"a\":1}");
    }

    @Test @DisplayName("YAML 1.1 values that were never ambiguous still resolve")
    void ordinaryResolutionUnchanged() throws Exception {
        assertThat(converter.yamlToJson("a: yes\nb: no\nc: true\n"))
              .isEqualTo("{\"a\":true,\"b\":false,\"c\":true}");
        assertThat(converter.yamlToJson("a: null\nb: ~\nc: 42\nd: 1.5\ne: 0x1F\n"))
              .isEqualTo("{\"a\":null,\"b\":null,\"c\":42,\"d\":1.5,\"e\":31}");
        // Anchors and merge keys still work — they are why this uses the composer.
        assertThat(converter.yamlToJson("base: &b {x: 1}\nuse:\n  <<: *b\n  y: 2\n"))
              .contains("\"x\":1").contains("\"y\":2");
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
