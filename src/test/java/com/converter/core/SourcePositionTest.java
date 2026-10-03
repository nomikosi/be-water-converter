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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Every parser's failure should put the caret on the character it complains about. */
@DisplayName("Parse error positions")
class SourcePositionTest {

    private final ConversionPipeline pipeline = new ConversionPipeline();

    @Test @DisplayName("JSON reports its 1-based line and column")
    void json() {
        Throwable failure = catchThrowable(() ->
              pipeline.normalizeToJson("{\n  \"a\": bad\n}", Formats.FMT_JSON, ConversionOptions.DEFAULTS));
        assertThat(SourcePosition.of(failure)).isEqualTo(new SourcePosition(2, 8));
    }

    @Test @DisplayName("YAML's 0-based mark becomes 1-based")
    void yaml() {
        Throwable failure = catchThrowable(() ->
              pipeline.normalizeToJson("a: 1\nb: [1, 2\n", Formats.FMT_YAML, ConversionOptions.DEFAULTS));
        SourcePosition position = SourcePosition.of(failure);
        assertThat(position).isNotNull();
        assertThat(position.line()).isEqualTo(3);
    }

    @Test @DisplayName("XML Format's DOM errors are located too")
    void xmlFormat() {
        Throwable failure = catchThrowable(() ->
              pipeline.formatInput("<a>\n  <b>\n</a>", Formats.FMT_XML, ConversionOptions.DEFAULTS));
        SourcePosition position = SourcePosition.of(failure);
        assertThat(position).isNotNull();
        assertThat(position.line()).isEqualTo(3);
        assertThat(position.column()).isPositive();
    }

    @Test @DisplayName("a position is found through wrapping exceptions")
    void wrapped() {
        Throwable failure = catchThrowable(() ->
              pipeline.normalizeToJson("[1,\n2,,]", Formats.FMT_JSON, ConversionOptions.DEFAULTS));
        assertThat(SourcePosition.of(new CompletionException(new RuntimeException(failure))))
              .isEqualTo(SourcePosition.of(failure))
              .isNotNull();
    }

    @Test @DisplayName("a parser's message reads as one line ending with the position, without its location text")
    void describe() {
        // On a second line, Jackson's location text opened a balloon of parser
        // internals for every syntax error.
        String json = SourcePosition.describe(catchThrowable(() ->
              pipeline.normalizeToJson("{\"a\": 1,, }", Formats.FMT_JSON, ConversionOptions.DEFAULTS)));
        assertThat(json).startsWith("Unexpected character").endsWith("(line 1, column 9)")
              .doesNotContain("\n").doesNotContain("Source").doesNotContain("REDACTED");
        assertThat(SourcePosition.describe(catchThrowable(() ->
              pipeline.normalizeToJson("a,b\n1,\"open\n", Formats.FMT_CSV, ConversionOptions.DEFAULTS))))
              .isEqualTo("Missing closing quote for value (line 3, column 1)");
        assertThat(SourcePosition.describe(catchThrowable(() ->
              pipeline.normalizeToJson("<a><b></a>", Formats.FMT_XML, ConversionOptions.DEFAULTS))))
              .startsWith("Unexpected close tag </a>; expected </b>. (line 1, column")
              .doesNotContain("\n").doesNotContain("row,col");
        assertThat(SourcePosition.describe(catchThrowable(() ->
              pipeline.normalizeToJson("a = = 1\n", Formats.FMT_TOML, ConversionOptions.DEFAULTS))))
              .matches("[^\\n]+ \\(line \\d+, column \\d+\\)").doesNotContain("Source");
        // YAML quotes the offending line, and the plugin's own messages are already words.
        Throwable yaml = catchThrowable(() ->
              pipeline.normalizeToJson("a: 1\nb: [1, 2\n", Formats.FMT_YAML, ConversionOptions.DEFAULTS));
        assertThat(SourcePosition.describe(yaml)).isEqualTo(yaml.getMessage());
        assertThat(SourcePosition.describe(new IllegalArgumentException("Input is empty"))).isEqualTo("Input is empty");
        assertThat(SourcePosition.describe(new IllegalStateException())).isEqualTo("IllegalStateException");
    }

    @Test @DisplayName("failures without a location, and cyclic causes, report none")
    void none() {
        assertThat(SourcePosition.of(new IllegalArgumentException("no position"))).isNull();
        assertThat(SourcePosition.of(null)).isNull();
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(SourcePosition.of(first)).isNull();
    }
}
