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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TomlLexicalGuardTest {
    private static final String LARGE = "1723600000000000000";
    private final TomlConverter converter = new TomlConverter();

    @ParameterizedTest @ValueSource(strings = {"\\\"\"\"", "\"\\\"\"", "\"\"\\\"", "\\\\\\\"\"\""})
    void escapedQuotesDoNotHideFollowingValues(String quotes) {
        String input = "text = \"\"\"before " + quotes + " after\"\"\"\nid = " + LARGE;
        assertThatThrownBy(() -> converter.tomlToJson(input))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TOML integer " + LARGE);
    }

    @ParameterizedTest @ValueSource(strings = {
          "1723600000000000000 = 'value'", "key.1723600000000000000 = 'value'",
          "[1723600000000000000]\nx = 'value'", "[[1723600000000000000]]\nx = 'value'",
          "x = { 1723600000000000000 = 'value' }", "x = [{1723600000000000000 = 'value'}]"})
    void numericKeysAreNotMistakenForValues(String input) throws Exception {
        assertThat(converter.tomlToJson(input)).contains("value", LARGE);
    }

    @ParameterizedTest @ValueSource(strings = {
          "x = [1, 1723600000000000000]", "x = [\n[1723600000000000000]\n]",
          "x = { y = 1723600000000000000 }", "x = [{a=1}, {b=1723600000000000000}]",
          "1723600000000000000 = 1723600000000000000", "x = [1, # comment\n1723600000000000000]"})
    void unsafeIntegersAreCheckedInEveryValuePosition(String input) {
        assertThatThrownBy(() -> converter.tomlToJson(input))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TOML integer " + LARGE);
    }

    @Test void numbersInsideStringsStayTextAndCommentsAreCountedCorrectly() throws Exception {
        String input = "text = \"\"\"before \\\"\"\" # " + LARGE + " after\"\"\" # real comment\nx = 2\n";
        assertThat(converter.tomlToJson(input)).contains(LARGE, "\"x\":2");
        assertThat(TomlConverter.countComments(input)).isEqualTo(1);
    }

    @Test void escapedQuotesDoNotHideFormattingLosses() {
        String prefix = "text = \"\"\"before \\\"\"\" after\"\"\"\n";
        var pipeline = new ConversionPipeline();
        assertThatThrownBy(() -> pipeline.formatInput(prefix + "date = 1979-05-27", "TOML", ConversionOptions.DEFAULTS.withInferTypes(false)))
              .hasMessageContaining("rewrite the date");
        assertThatThrownBy(() -> pipeline.formatInput(prefix + "n = 0xFF", "TOML", ConversionOptions.DEFAULTS.withInferTypes(false)))
              .hasMessageContaining("hexadecimal");
    }
}
