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

import com.converter.converter.CsvConverter.CsvFormat;
import com.converter.converter.CsvConverter.CsvMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CSV delimiter/quote options and key sorting")
class CsvFormatAndSortKeysTest {

    private CsvConverter csv;
    private ConversionPipeline pipeline;

    @BeforeEach void setUp() {
        csv = new CsvConverter();
        pipeline = new ConversionPipeline();
    }

    // ── CSV delimiter ────────────────────────────────────────────────────

    @Test @DisplayName("semicolon-delimited input parses into the right columns")
    void semicolonInput() throws Exception {
        String json = csv.csvToJson("name;age\nAda;36\n", true, CsvFormat.SEMICOLON);
        assertThat(json).contains("\"name\" : \"Ada\"").contains("\"age\" : 36");
    }

    @Test @DisplayName("comma parsing of semicolon input yields one bogus column")
    void semicolonNeedsTheOption() throws Exception {
        // Without the option the whole line is a single column — the exact
        // failure European CSV users hit.
        String json = csv.csvToJson("name;age\nAda;36\n", true, CsvFormat.DEFAULT);
        assertThat(json).contains("name;age");
    }

    @Test @DisplayName("tab-delimited input parses")
    void tabInput() throws Exception {
        String json = csv.csvToJson("name\tage\nAda\t36\n", true, CsvFormat.TAB);
        assertThat(json).contains("\"name\" : \"Ada\"").contains("\"age\" : 36");
    }

    @Test @DisplayName("output honours the configured delimiter")
    void semicolonOutput() throws Exception {
        String out = csv.jsonToCsv(pipeline.parseJson("[{\"a\":1,\"b\":2}]"),
              CsvMode.FLAT_FIRST, CsvFormat.SEMICOLON);
        assertThat(out).contains("a;b").contains("1;2").doesNotContain("a,b");
    }

    @Test @DisplayName("semicolon round-trip preserves values")
    void semicolonRoundTrip() throws Exception {
        String original = "name;city\nAda;London\nGrace;NYC\n";
        String json = csv.csvToJson(original, false, CsvFormat.SEMICOLON);
        String back = csv.jsonToCsv(pipeline.parseJson(json), CsvMode.FLAT_FIRST,
              CsvFormat.SEMICOLON);
        assertThat(back.replace("\r\n", "\n").strip())
              .isEqualTo(original.strip());
    }

    @Test @DisplayName("a value containing the delimiter is quoted")
    void delimiterInValueIsQuoted() throws Exception {
        String out = csv.jsonToCsv(pipeline.parseJson("[{\"a\":\"x;y\"}]"),
              CsvMode.FLAT_FIRST, CsvFormat.SEMICOLON);
        assertThat(out).contains("\"x;y\"");
    }

    @Test @DisplayName("existing comma behaviour is unchanged by default")
    void defaultsUnchanged() throws Exception {
        assertThat(csv.csvToJson("a,b\n1,2\n", true)).contains("\"a\" : 1").contains("\"b\" : 2");
    }

    // ── Key sorting ──────────────────────────────────────────────────────

    @Test @DisplayName("object keys are sorted recursively, array order preserved")
    void sortsRecursively() throws Exception {
        String sorted = pipeline.sortKeys("{\"b\":1,\"a\":{\"z\":1,\"y\":2},\"c\":[3,1,2]}");
        assertThat(sorted.indexOf("\"a\"")).isLessThan(sorted.indexOf("\"b\""));
        assertThat(sorted.indexOf("\"y\"")).isLessThan(sorted.indexOf("\"z\""));
        // Arrays are ordered data, not key sets — order must survive.
        assertThat(sorted).contains("[3,1,2]");
    }

    @Test @DisplayName("differently-ordered equivalent documents canonicalize identically")
    void canonicalFormIsStable() throws Exception {
        assertThat(pipeline.sortKeys("{\"b\":1,\"a\":2}"))
              .isEqualTo(pipeline.sortKeys("{\"a\":2,\"b\":1}"));
    }

    @Test @DisplayName("sortKeys applies through normalizeToJson when enabled")
    void sortViaOptions() throws Exception {
        String pivot = pipeline.normalizeToJson("{\"b\":1,\"a\":2}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS.withSortKeys(true));
        assertThat(pivot.indexOf("\"a\"")).isLessThan(pivot.indexOf("\"b\""));
    }

    @Test @DisplayName("sorting is off by default, preserving document order")
    void offByDefault() throws Exception {
        String pivot = pipeline.normalizeToJson("{\"b\":1,\"a\":2}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS);
        assertThat(pivot.indexOf("\"b\"")).isLessThan(pivot.indexOf("\"a\""));
    }

    @Test @DisplayName("sorted keys carry through to a rendered target format")
    void sortReachesOutput() throws Exception {
        String pivot = pipeline.normalizeToJson("{\"b\":1,\"a\":2}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS.withSortKeys(true));
        String yaml = pipeline.renderFromJson(pivot, ConversionPipeline.FMT_YAML,
              ConversionOptions.DEFAULTS);
        assertThat(yaml.indexOf("a:")).isLessThan(yaml.indexOf("b:"));
    }
}
