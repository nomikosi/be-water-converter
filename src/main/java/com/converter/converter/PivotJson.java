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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Reader settings for the JSON pivot every conversion passes through.
 *
 * <p>The pivot is text, so each converter re-parses it — and a default
 * {@link ObjectMapper} loses on that hop exactly what {@code ConversionPipeline}
 * takes care to keep. {@code 1e400} came back as the string {@code "Infinity"},
 * {@code 1e-400} as {@code 0.0}, and a 30-digit decimal was cut to a
 * {@code double}. The pipeline had the fix; every converter then built a plain
 * mapper and threw it away again, so the setting belongs here, once.
 *
 * <p>Exact big decimals matter as much as big ones: Jackson's default node
 * factory calls {@code stripTrailingZeros}, which rewrote {@code 1.0} as
 * {@code 1} and {@code 100.00} as {@code 1E+2} — a value the user never typed,
 * written back over their document by Format.
 */
public final class PivotJson {

    private PivotJson() {}

    /** A mapper that carries every number through the pivot unchanged. */
    public static ObjectMapper mapper() {
        return JsonMapper.builder()
              .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
              // The constructor, not withExactBigDecimals(true): Jackson 2.21
              // deprecates the static factory and keeps this form.
              .nodeFactory(new JsonNodeFactory(true))
              .build();
    }
}
