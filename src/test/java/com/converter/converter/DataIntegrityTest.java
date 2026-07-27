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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regressions for input that used to be silently mangled. Every case here
 * previously produced wrong output with no error — several through the Format
 * action, which writes its result straight back over the user's editor.
 *
 * <p>Assertions go through the parsed tree rather than the pivot string, because
 * only the JSON branch of {@code normalizeToJson} returns compact JSON; the
 * others still indent.
 */
@DisplayName("Data integrity")
class DataIntegrityTest {

    private ConversionPipeline pipeline;
    private static final String BOM = "﻿";

    @BeforeEach void setUp() { pipeline = new ConversionPipeline(); }

    private JsonNode pivot(String input, String fmt) throws Exception {
        return pipeline.parseJson(pipeline.normalizeToJson(input, fmt, ConversionOptions.DEFAULTS));
    }

    // ── Trailing content ──────────────────────────────────────────────────

    @Test @DisplayName("JSONL is rejected rather than truncated to its first record")
    void jsonlRejected() {
        String jsonl = "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n";
        assertThatThrownBy(() -> pipeline.normalizeToJson(jsonl, ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS)).isInstanceOf(Exception.class);
        // Format is the dangerous path: it overwrites the input editor.
        assertThatThrownBy(() -> pipeline.formatInput(jsonl, ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS)).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("garbage after a valid value is an error, not silently dropped")
    void trailingGarbageRejected() {
        assertThatThrownBy(() -> pipeline.normalizeToJson("{\"a\":1} oops",
              ConversionPipeline.FMT_JSON, ConversionOptions.DEFAULTS))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("a single value with only whitespace after it is still fine")
    void trailingWhitespaceStillValid() throws Exception {
        assertThat(pipeline.normalizeToJson("  {\"a\":1}  \n\n", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS)).isEqualTo("{\"a\":1}");
    }

    // ── YAML anchors and merge keys ───────────────────────────────────────

    @Test @DisplayName("an alias resolves to the anchored value, not the anchor name")
    void aliasResolves() throws Exception {
        JsonNode tree = pivot("a: &x 1\nb: *x\n", ConversionPipeline.FMT_YAML);
        // Previously produced b = "x": wrong value AND wrong type.
        assertThat(tree.get("b").isNumber()).isTrue();
        assertThat(tree.get("b").asInt()).isEqualTo(1);
    }

    @Test @DisplayName("a merge key merges instead of surviving as a literal '<<' key")
    void mergeKeyMerges() throws Exception {
        String yaml = """
              base: &b
                image: nginx
                restart: always
              web:
                <<: *b
                image: apache
              """;
        JsonNode web = pivot(yaml, ConversionPipeline.FMT_YAML).get("web");
        assertThat(web.has("<<")).isFalse();
        // The local override wins and the inherited key survives.
        assertThat(web.get("image").asText()).isEqualTo("apache");
        assertThat(web.get("restart").asText()).isEqualTo("always");
    }

    @Test @DisplayName("an anchored collection is expanded at every alias site")
    void anchoredCollection() throws Exception {
        JsonNode tree = pivot("defs: &d [1, 2]\nx: *d\ny: *d\n", ConversionPipeline.FMT_YAML);
        assertThat(tree.get("x").toString()).isEqualTo("[1,2]");
        assertThat(tree.get("y").toString()).isEqualTo("[1,2]");
    }

    @Test @DisplayName("multi-document YAML still becomes an array")
    void multiDocumentStillWorks() throws Exception {
        JsonNode tree = pivot("a: 1\n---\nb: 2\n", ConversionPipeline.FMT_YAML);
        assertThat(tree.isArray()).isTrue();
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("a").asInt()).isEqualTo(1);
        assertThat(tree.get(1).get("b").asInt()).isEqualTo(2);
    }

    // ── BOM ───────────────────────────────────────────────────────────────

    @Test @DisplayName("a BOM does not become part of the first CSV column name")
    void bomStrippedFromCsv() throws Exception {
        JsonNode row = pivot(BOM + "id,name\n1,Ada\n", ConversionPipeline.FMT_CSV).get(0);
        assertThat(row.has("id")).isTrue();
        assertThat(row.get("id").asInt()).isEqualTo(1);
        assertThat(row.toString()).doesNotContain(BOM);
    }

    @Test @DisplayName("BOM-prefixed JSON, TOML and XML parse instead of throwing")
    void bomStrippedElsewhere() throws Exception {
        assertThat(pipeline.normalizeToJson(BOM + "{\"a\":1}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS)).isEqualTo("{\"a\":1}");
        assertThat(pivot(BOM + "a = 1\n", ConversionPipeline.FMT_TOML).get("a").asInt()).isEqualTo(1);
        assertThat(pivot(BOM + "<r><a>1</a></r>", ConversionPipeline.FMT_XML).has("a")).isTrue();
    }

    @Test @DisplayName("detection sees through a BOM")
    void bomStrippedInDetection() {
        assertThat(ConversionPipeline.detectFormat(BOM + "{\"a\":1}"))
              .isEqualTo(ConversionPipeline.FMT_JSON);
        assertThat(ConversionPipeline.detectFormat(BOM + "<r/>"))
              .isEqualTo(ConversionPipeline.FMT_XML);
    }

    // ── Format must not rewrite data ──────────────────────────────────────

    @Test @DisplayName("Format leaves CSV cell values exactly as written")
    void formatDoesNotRetypeCsv() throws Exception {
        // Format is a layout action. Inferring types here rewrote the user's
        // data in place: 1.50 became 1.5 and the literal text null was erased.
        String formatted = pipeline.formatInput("sku,price,note\nA1,1.50,null\n",
              ConversionPipeline.FMT_CSV, ConversionOptions.DEFAULTS);
        assertThat(formatted).contains("1.50").contains("null");
    }

    @Test @DisplayName("Convert still infers CSV types — only Format is literal")
    void convertStillInfers() throws Exception {
        assertThat(pivot("a\n1\n", ConversionPipeline.FMT_CSV).get(0).get("a").isNumber()).isTrue();
    }

    // ── Duplicate CSV headers ─────────────────────────────────────────────

    @Test @DisplayName("repeated header names keep every column instead of collapsing")
    void duplicateHeadersPreserved() throws Exception {
        // The trailing empty duplicate used to overwrite the populated column.
        JsonNode row = pivot("Notes,Amount,Notes\nkeep-me,10,\n", ConversionPipeline.FMT_CSV).get(0);
        assertThat(row.get("Notes").asText()).isEqualTo("keep-me");
        assertThat(row.has("Notes_2")).isTrue();
    }

    @Test @DisplayName("an empty header cell gets an addressable name")
    void emptyHeaderNamed() throws Exception {
        assertThat(pivot("a,,c\n1,2,3\n", ConversionPipeline.FMT_CSV).get(0).has("column_2"))
              .isTrue();
    }

    // ── Number fidelity ───────────────────────────────────────────────────

    @Test @DisplayName("long decimals keep every digit")
    void precisionPreserved() throws Exception {
        assertThat(pipeline.normalizeToJson("{\"v\":0.1234567890123456789}",
              ConversionPipeline.FMT_JSON, ConversionOptions.DEFAULTS))
              .contains("0.1234567890123456789");
    }

    @Test @DisplayName("a huge magnitude stays a number instead of becoming the string Infinity")
    void noInfinityString() throws Exception {
        String out = pipeline.normalizeToJson("{\"v\":1e400}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS);
        assertThat(out).doesNotContain("Infinity");
        assertThat(pipeline.parseJson(out).get("v").isNumber()).isTrue();
    }

    @Test @DisplayName("a tiny magnitude is not flattened to zero")
    void noUnderflowToZero() throws Exception {
        String out = pipeline.normalizeToJson("{\"v\":1e-400}", ConversionPipeline.FMT_JSON,
              ConversionOptions.DEFAULTS);
        assertThat(pipeline.parseJson(out).get("v").decimalValue().signum()).isEqualTo(1);
    }

    @Test @DisplayName("Compare no longer calls documents equal when they differ past digit 17")
    void comparePrecision() throws Exception {
        assertThat(pipeline.canonicalJson("{\"v\":0.12345678901234567}", ConversionPipeline.FMT_JSON))
              .isNotEqualTo(pipeline.canonicalJson("{\"v\":0.12345678901234568}",
                    ConversionPipeline.FMT_JSON));
    }
}
