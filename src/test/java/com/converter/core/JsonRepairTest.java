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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/** Repairing truncated JSON without breaking what the lenient reader already accepts. */
@DisplayName("JSON repair")
class JsonRepairTest {

    private final ConversionPipeline pipeline = new ConversionPipeline();
    private final ConversionOptions opts = ConversionOptions.DEFAULTS;
    private final ObjectMapper json = new ObjectMapper();

    @Test @DisplayName("autoClose repairs an unterminated string and brackets")
    void autoCloseUnterminatedString() throws Exception {
        String repaired = JsonRepair.autoClose("{\"name\": \"Al");
        assertThat(repaired).isEqualTo("{\"name\": \"Al\"}");
        json.readTree(repaired); // must parse
    }

    @Test @DisplayName("autoClose repairs unclosed brackets")
    void autoCloseBrackets() {
        assertThat(JsonRepair.autoClose("{\"a\": [1, 2")).isEqualTo("{\"a\": [1, 2]}");
    }

    @Test @DisplayName("autoClose repairs a dangling escape into parseable JSON")
    void autoCloseDanglingEscape() throws Exception {
        json.readTree(JsonRepair.autoClose("{\"path\": \"C:\\"));
    }

    @Test @DisplayName("autoClose leaves complete JSON untouched")
    void autoCloseNoOp() {
        String complete = "{\"a\": [1, 2]}";
        assertThat(JsonRepair.autoClose(complete)).isEqualTo(complete);
    }

    @Test @DisplayName("autoClose leaves input the lenient reader already accepts alone")
    void autoCloseRespectsCommentsAndSingleQuotes() throws Exception {
        // A brace inside a comment or a single-quoted string was counted as
        // real, and the appended closer made valid input fail to parse.
        assertThat(JsonRepair.autoClose("{\"a\":1} // trailing {")).isEqualTo("{\"a\":1} // trailing {");
        assertThat(JsonRepair.autoClose("{'a':'it {'}")).isEqualTo("{'a':'it {'}");
        assertThat(JsonRepair.autoClose("{\"a\":1} /* note [ */")).isEqualTo("{\"a\":1} /* note [ */");
        assertThat(JsonRepair.autoClose("{\"a\":1} # hash {")).isEqualTo("{\"a\":1} # hash {");
        // Genuinely unclosed input is still completed.
        assertThat(JsonRepair.autoClose("{\"a\":[1,2")).isEqualTo("{\"a\":[1,2]}");
        assertThat(JsonRepair.autoClose("{\"a\":\"unterminated")).isEqualTo("{\"a\":\"unterminated\"}");
        // And the completed forms parse.
        assertThat(pipeline.formatInput("{\"a\":1} // trailing {", Formats.FMT_JSON, opts))
              .contains("\"a\"");
    }

    @Test @DisplayName("autoClose puts its closers after a trailing line comment, not inside it")
    void autoCloseClosesAfterTrailingLineComment() throws Exception {
        // {"a":1 // note} is a document whose brace is part of the comment.
        assertThat(JsonRepair.autoClose("{\"a\":1 // note")).isEqualTo("{\"a\":1 // note\n}");
        assertThat(JsonRepair.autoClose("{\"a\":[1 # note")).isEqualTo("{\"a\":[1 # note\n]}");
        assertThat(pipeline.formatInput("{\"a\":1 // note", Formats.FMT_JSON, opts))
              .contains("\"a\" : 1");
        assertThat(pipeline.normalizeToJson("{\"a\":1 // note", Formats.FMT_JSON, opts))
              .isEqualTo("{\"a\":1}");
        // Complete input ending in a comment gains nothing.
        assertThat(JsonRepair.autoClose("{\"a\":1} // note")).isEqualTo("{\"a\":1} // note");
    }
}
