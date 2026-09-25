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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class YamlFormattingTypesTest {
    private final ConversionPipeline pipeline = new ConversionPipeline();

    @ParameterizedTest @ValueSource(strings = {
          "1: value\n",
          "true: value\n",
          "null: value\n",
          "1.5: value\n",
          "? [a, b]\n: value\n",
          "? {a: b}\n: value\n",
          "rows:\n  - true: value\n",
          "defaults: &d {1: x}\ncopy: {<<: *d}\n",
          "!!timestamp 2024-01-01",
          "v: !!timestamp 2024-01-01T12:30:00Z",
          "v: !!set {a: null}",
          "v: !!omap [{a: 1}, {b: 2}]",
          "v: !!pairs [{a: 1}, {a: 2}]"
    })
    void refusesYamlTypesThatFormattingCannotPreserve(String input) {
        assertThatThrownBy(() -> pipeline.formatInput(input, "YAML", false))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Format cannot preserve").hasMessageContaining("left as it is");
    }

    @ParameterizedTest @ValueSource(strings = {
          "\"1\": value\n\"true\": other\n\"null\": text\n",
          "v: \"!!timestamp 2024-01-01\"\n",
          "a: !!str 123\nb: !!int 42\nc: !!bool true\nd: !!null null\n",
          "v: !!binary SGVsbG8=\n",
          "base: &d {a: 1.10}\ncopy: {<<: *d, b: true}\n",
          "null\n---\nrows: [{z: 1.10, a: yes}]\n"
    })
    void supportedValuesAndStringKeysKeepTheirMeaning(String input) throws Exception {
        for (boolean sort : new boolean[]{false, true}) {
            String formatted = pipeline.formatInput(input, "YAML", ConversionOptions.DEFAULTS.withSortKeys(sort));
            assertThat(pipeline.canonicalJson(formatted, "YAML")).isEqualTo(pipeline.canonicalJson(input, "YAML"));
        }
    }
}
