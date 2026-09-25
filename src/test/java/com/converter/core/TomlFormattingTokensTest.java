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

class TomlFormattingTokensTest {
    private final ConversionPipeline pipeline = new ConversionPipeline();

    @ParameterizedTest @ValueSource(strings = {
          "[2024-01-01]\nx = 1\n",
          "[0xFF]\nx = 1\n",
          "[inf]\nx = 1\n",
          "[1_000]\nx = 1\n",
          "[[nan]]\nx = 1\n",
          "x = {inf = 1, 2024-01-01 = 2, 0xFF = 3}\n",
          "inf = \"1979-05-27t07:32:00Z\"\n",
          "x = \"\"\"a \\\"\"\" b\"\"\"\n[2024-01-01]\ny = 1\n"
    })
    void keysAndQuotedTextDoNotTriggerValueGuards(String input) throws Exception {
        String formatted = pipeline.formatInput(input, "TOML", false);
        assertThat(pipeline.canonicalJson(formatted, "TOML")).isEqualTo(pipeline.canonicalJson(input, "TOML"));
    }

    @ParameterizedTest @ValueSource(strings = {
          "d = 1979-05-27t07:32:00Z",
          "d = 1979-05-27T07:32:00Z",
          "d = 1979-05-27 07:32:00Z",
          "d = 1979-05-27",
          "d = 07:32:00",
          "d = [1, 1979-05-27t07:32:00Z]",
          "d = {v = 1979-05-27t07:32:00Z}",
          "d = [[1, 0xFF]]",
          "d = {v = -inf}",
          "d = [1, 1_000]",
          "text = \"\"\"a \\\"\"\" b\"\"\"\nd = 1979-05-27t07:32:00Z"
    })
    void refusesLossyValuesInEveryContainer(String input) {
        assertThatThrownBy(() -> pipeline.formatInput(input, "TOML", false))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Format would rewrite");
    }
}
