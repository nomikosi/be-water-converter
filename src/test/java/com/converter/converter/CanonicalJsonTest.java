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
        String json = pipeline.canonicalJson("{\"b\":2,\"a\":1}", ConversionPipeline.FMT_JSON);
        String yaml = pipeline.canonicalJson("a: 1\nb: 2\n", ConversionPipeline.FMT_YAML);
        assertThat(json).isEqualTo(yaml);
    }

    @Test @DisplayName("key order does not create a difference")
    void keyOrderIrrelevant() throws Exception {
        assertThat(pipeline.canonicalJson("{\"x\":{\"p\":1,\"q\":2}}", ConversionPipeline.FMT_JSON))
              .isEqualTo(pipeline.canonicalJson("{\"x\":{\"q\":2,\"p\":1}}",
                    ConversionPipeline.FMT_JSON));
    }

    @Test @DisplayName("a genuine difference survives canonicalization")
    void realDifferenceSurvives() throws Exception {
        assertThat(pipeline.canonicalJson("{\"a\":1}", ConversionPipeline.FMT_JSON))
              .isNotEqualTo(pipeline.canonicalJson("{\"a\":2}", ConversionPipeline.FMT_JSON));
    }

    @Test @DisplayName("array order is a real difference, not noise")
    void arrayOrderMatters() throws Exception {
        assertThat(pipeline.canonicalJson("[1,2]", ConversionPipeline.FMT_JSON))
              .isNotEqualTo(pipeline.canonicalJson("[2,1]", ConversionPipeline.FMT_JSON));
    }

    @Test @DisplayName("output is indented, so the diff viewer shows line-level changes")
    void isPrettyPrinted() throws Exception {
        assertThat(pipeline.canonicalJson("{\"a\":{\"b\":1}}", ConversionPipeline.FMT_JSON))
              .contains("\n");
    }
}
