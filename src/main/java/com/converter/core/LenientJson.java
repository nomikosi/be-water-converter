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

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * How JSON the user wrote is read: leniently, with numbers kept exact, and
 * refused rather than truncated. Shared by conversion and by Format.
 */
final class LenientJson {

    private LenientJson() {}

    /**
     * Lenient read settings for JSON input: accepts comments, trailing commas,
     * single quotes and unquoted field names (pasted JS object literals).
     * Input is normalised through these into strict JSON before it reaches the
     * downstream converters.
     */
    private static JsonMapper.Builder lenientReader() {
        return PivotJson.builder()
              .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
              .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
              .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
              .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
              .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
              // Without this, readTree stops at the first top-level value and
              // silently discards the rest: JSONL kept only its first record and
              // trailing garbage was accepted. Failing is strictly better than
              // Format overwriting the editor with a truncated document.
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              // A repeated key kept only its last value, silently: {"a":1,"a":2}
              // read as {"a":2}, and Format wrote the half-document back. The
              // YAML reader already refuses duplicates; JSON now does the same.
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    /** Indenting mapper — for JSON the user actually sees. */
    static final ObjectMapper PRETTY = lenientReader().enable(SerializationFeature.INDENT_OUTPUT)
          .defaultPrettyPrinter(PivotJson.prettyPrinter()).build();

    /**
     * Compact mapper for the internal JSON pivot. The pivot is re-parsed by the
     * next stage and never displayed, so indenting it only inflates the string:
     * on a 20k-row array the indented pivot measured ~1.5x the compact one.
     */
    static final ObjectMapper COMPACT = lenientReader().build();

    /**
     * Reads user-supplied JSON, refusing a document with no value in it.
     *
     * <p>The reader returns a missing node for content that is only comments
     * and whitespace, and that node serialises as {@code null}: a file holding
     * nothing but {@code // todo} converted to the YAML document {@code null}
     * and reported success.
     */
    static JsonNode read(ObjectMapper mapper, String json) throws Exception {
        JsonNode tree = mapper.readTree(json);
        if (tree == null || tree.isMissingNode())
            throw new IllegalArgumentException(
                  "Input JSON contains no value: only comments or whitespace.");
        return tree;
    }

    /** Re-serialises JSON compactly as the strict pivot, refusing a document that holds no value. */
    static String compact(String json) throws Exception {
        return COMPACT.writeValueAsString(read(COMPACT, json));
    }

    /** Re-indents JSON for display, refusing a document that holds no value. */
    static String pretty(String json) throws Exception {
        return PRETTY.writeValueAsString(read(PRETTY, json));
    }

    /**
     * Recursively sorts object keys alphabetically, leaving array order intact.
     * Makes output diffable across runs and across sources that emit the same
     * data in different key orders.
     */
    static String sortKeys(String json) throws Exception {
        return COMPACT.writeValueAsString(JsonTrees.sorted(COMPACT.readTree(json)));
    }
}
