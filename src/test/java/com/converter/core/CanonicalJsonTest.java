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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Canonical JSON (the basis of the compare action)")
class CanonicalJsonTest {

    private ConversionPipeline pipeline;

    @BeforeEach void setUp() { pipeline = new ConversionPipeline(); }

    @Test @DisplayName("the same data in JSON and YAML canonicalizes identically")
    void acrossFormats() throws Exception {
        String json = pipeline.canonicalJson("{\"b\":2,\"a\":1}", Formats.FMT_JSON, ConversionOptions.DEFAULTS);
        String yaml = pipeline.canonicalJson("a: 1\nb: 2\n", Formats.FMT_YAML, ConversionOptions.DEFAULTS);
        assertThat(json).isEqualTo(yaml);
    }

    @Test @DisplayName("key order does not create a difference")
    void keyOrderIrrelevant() throws Exception {
        assertThat(pipeline.canonicalJson("{\"x\":{\"p\":1,\"q\":2}}", Formats.FMT_JSON, ConversionOptions.DEFAULTS))
              .isEqualTo(pipeline.canonicalJson("{\"x\":{\"q\":2,\"p\":1}}",
                    Formats.FMT_JSON, ConversionOptions.DEFAULTS));
    }

    @Test @DisplayName("a genuine difference survives canonicalization")
    void realDifferenceSurvives() throws Exception {
        assertThat(pipeline.canonicalJson("{\"a\":1}", Formats.FMT_JSON, ConversionOptions.DEFAULTS))
              .isNotEqualTo(pipeline.canonicalJson("{\"a\":2}", Formats.FMT_JSON, ConversionOptions.DEFAULTS));
    }

    @Test @DisplayName("array order is a real difference, not noise")
    void arrayOrderMatters() throws Exception {
        assertThat(pipeline.canonicalJson("[1,2]", Formats.FMT_JSON, ConversionOptions.DEFAULTS))
              .isNotEqualTo(pipeline.canonicalJson("[2,1]", Formats.FMT_JSON, ConversionOptions.DEFAULTS));
    }

    @Test @DisplayName("output is indented, so the diff viewer shows line-level changes")
    void isPrettyPrinted() throws Exception {
        assertThat(pipeline.canonicalJson("{\"a\":{\"b\":1}}", Formats.FMT_JSON, ConversionOptions.DEFAULTS))
              .contains("\n");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
          "1,1.0", "1.00,1e0", "1000,1e3", "0,-0.00", "1e400,10e399", "1e-400,0.1e-399"})
    void numericRepresentationsCompareByExactValue(String left, String right) throws Exception {
        assertThat(pipeline.canonicalJson("{\"nested\":[" + left + "]}", "JSON", ConversionOptions.DEFAULTS))
              .isEqualTo(pipeline.canonicalJson("{\"nested\":[" + right + "]}", "JSON", ConversionOptions.DEFAULTS));
    }

    @Test void numericComparisonPreservesPrecisionAndTypes() throws Exception {
        assertThat(pipeline.canonicalJson("[1.00000000000000000001]", "JSON", ConversionOptions.DEFAULTS))
              .isNotEqualTo(pipeline.canonicalJson("[1.00000000000000000002]", "JSON", ConversionOptions.DEFAULTS));
        assertThat(pipeline.canonicalJson("[1e-400]", "JSON", ConversionOptions.DEFAULTS))
              .isNotEqualTo(pipeline.canonicalJson("[0]", "JSON", ConversionOptions.DEFAULTS));
        assertThat(pipeline.canonicalJson("[1]", "JSON", ConversionOptions.DEFAULTS))
              .isNotEqualTo(pipeline.canonicalJson("[\"1\"]", "JSON", ConversionOptions.DEFAULTS));
        assertThat(pipeline.canonicalJson("[1.0]", "JSON", ConversionOptions.DEFAULTS))
              .isEqualTo(pipeline.canonicalJson("- 1.00\n", "YAML", ConversionOptions.DEFAULTS));
    }

    @Test void comparisonDoesNotChangeFormattingOrSortingFidelity() throws Exception {
        String input = "{\"b\":100.00,\"a\":1.0}";
        pipeline.canonicalJson(input, "JSON", ConversionOptions.DEFAULTS);
        assertThat(pipeline.formatInput(input, "JSON", ConversionOptions.DEFAULTS))
              .contains("100.00", "1.0");
        assertThat(pipeline.sortKeys(input)).isEqualTo("{\"a\":1.0,\"b\":100.00}");
    }

}
