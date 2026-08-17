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

/**
 * Numbers must survive the JSON pivot unchanged.
 *
 * <p>The pipeline's reader always kept them; every converter then built a plain
 * ObjectMapper and re-parsed the pivot with it, which threw that away again.
 */
@DisplayName("Numeric fidelity through the pivot")
class NumericFidelityTest {

    private static final String LONG_DECIMAL = "1.234567890123456789012345678901";

    private final ConversionPipeline pipeline = new ConversionPipeline();
    private final ConversionOptions opts = ConversionOptions.DEFAULTS;

    private static String flat(String s) { return s.replaceAll("\\s+", ""); }

    @Test @DisplayName("Format keeps the decimal the user actually wrote")
    void formatKeepsDecimalsExact() throws Exception {
        // Jackson's default node factory calls stripTrailingZeros, so Format
        // rewrote the document with numbers nobody typed.
        assertThat(flat(pipeline.formatInput("{\"a\":1.0}", ConversionPipeline.FMT_JSON, opts)))
              .isEqualTo("{\"a\":1.0}");
        assertThat(flat(pipeline.formatInput("{\"a\":100.00}", ConversionPipeline.FMT_JSON, opts)))
              .isEqualTo("{\"a\":100.00}");
        assertThat(flat(pipeline.formatInput("{\"a\":0.10}", ConversionPipeline.FMT_JSON, opts)))
              .isEqualTo("{\"a\":0.10}");
    }

    @Test @DisplayName("magnitudes beyond double survive every render target")
    void hugeAndTinyMagnitudesSurvive() throws Exception {
        // 1e400 came back as the STRING "Infinity" and 1e-400 as 0.0.
        assertThat(new JsonYamlConverter().jsonToYaml("{\"a\":1e400}"))
              .contains("1E+400").doesNotContain("Infinity");
        assertThat(new TomlConverter().jsonToToml("{\"a\":1e-400}"))
              .contains("1E-400").doesNotContain("0.0");
        assertThat(flat(pipeline.formatInput("{\"a\":1e400}", ConversionPipeline.FMT_JSON, opts)))
              .isEqualTo("{\"a\":1E+400}");
    }

    @Test @DisplayName("a decimal longer than double keeps every digit")
    void longDecimalsKeepTheirDigits() throws Exception {
        String json = "{\"a\":" + LONG_DECIMAL + "}";
        assertThat(new JsonYamlConverter().jsonToYaml(json)).contains(LONG_DECIMAL);
        assertThat(new TomlConverter().jsonToToml(json)).contains(LONG_DECIMAL);
        assertThat(new JsonXmlConverter().jsonToXml(json)).contains(LONG_DECIMAL);
        assertThat(flat(pipeline.formatInput(json, ConversionPipeline.FMT_JSON, opts)))
              .isEqualTo(flat(json));
    }

    @Test @DisplayName("inferred CSV and XML scalars are not routed through double")
    void inferredScalarsKeepPrecision() throws Exception {
        CsvConverter csv = new CsvConverter();
        // 1e-400 underflowed to 0.0 and a 30-digit decimal was cut to 17.
        assertThat(csv.csvToJson("a\n1e-400\n", true)).contains("1E-400").doesNotContain("0.0");
        assertThat(csv.csvToJson("a\n" + LONG_DECIMAL + "\n", true)).contains(LONG_DECIMAL);
        assertThat(csv.csvToJson("a\n1e400\n", true)).contains("1E+400");
        // Unchanged: leading zeros and oversized integers still stay text.
        assertThat(csv.csvToJson("a\n007\n", true)).contains("\"007\"");
    }
}
