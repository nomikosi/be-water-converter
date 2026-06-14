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
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.util.*;

public class CsvConverter {

    public enum CsvMode {
        /**
         * Only the FIRST array-of-objects is expanded into rows.
         * All other object-arrays are serialised as a JSON string in one cell.
         */
        FLAT_FIRST,
        /**
         * Full Cartesian product: every array-of-objects is cross-joined.
         * N arrays with sizes s1, s2, …, sN produce s1 × s2 × … × sN rows.
         */
        CROSS_JOIN
    }

    private final ObjectMapper jsonMapper;
    private final CsvMapper   csvMapper;

    public CsvConverter() {
        jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        csvMapper  = new CsvMapper();
    }

    // ── CSV → JSON ────────────────────────────────────────────────────────────

    public String csvToJson(String csv) throws Exception {
        if (csv == null || csv.isBlank())
            throw new IllegalArgumentException("Input CSV must not be empty");
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        MappingIterator<Map<String, String>> it =
              csvMapper.readerFor(Map.class).with(schema).readValues(csv);
        return jsonMapper.writeValueAsString(it.readAll());
    }

    // ── JSON → CSV (mode-aware) ───────────────────────────────────────────────

    /** Convenience overload – defaults to FLAT_FIRST for backwards compatibility. */
    public String jsonToCsv(String json) throws Exception {
        return jsonToCsv(json, CsvMode.FLAT_FIRST);
    }

    public String jsonToCsv(String json, CsvMode mode) throws Exception {
        JsonNode root = jsonMapper.readTree(json);

        // Normalise: wrap a bare object in a single-element array
        if (root.isObject()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = jsonMapper.createArrayNode();
            arr.add(root);
            root = arr;
        }

        if (!root.isArray())
            throw new IllegalArgumentException(
                  "JSON must be an array of objects or a single object for CSV output");

        // Expand every top-level element according to the chosen mode
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode element : root) {
            if (!element.isObject()) continue;
            List<Map<String, String>> expanded =
                  (mode == CsvMode.CROSS_JOIN)
                        ? expandCrossJoin(element, "")
                        : expandFlatFirst(element, "");
            rows.addAll(expanded);
        }

        if (rows.isEmpty()) return "";

        // Collect ordered headers (insertion order from first row, then rest)
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (Map<String, String> row : rows) headers.addAll(row.keySet());

        CsvSchema.Builder sb = CsvSchema.builder().setUseHeader(true);
        for (String h : headers) sb.addColumn(h);

        // Ensure every row has a value (possibly "") for every header
        List<Map<String, String>> normalised = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> r = new LinkedHashMap<>();
            for (String h : headers) r.put(h, row.getOrDefault(h, ""));
            normalised.add(r);
        }

        return csvMapper.writer(sb.build()).writeValueAsString(normalised);
    }

    // ── Row-count estimation (no row materialisation) ────────────────────────

    /** Cap used by the estimator so Cartesian products cannot overflow. */
    public static final long ESTIMATE_CAP = 1_000_000_000L;

    /**
     * Estimates how many CSV data rows {@link #jsonToCsv(String, CsvMode)} would
     * produce, without building them. Useful for warning about row explosion
     * under {@link CsvMode#CROSS_JOIN} before running the conversion.
     * The result is capped at {@link #ESTIMATE_CAP}.
     */
    public long estimateRowCount(String json, CsvMode mode) throws Exception {
        JsonNode root = jsonMapper.readTree(json);
        if (root.isObject()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = jsonMapper.createArrayNode();
            arr.add(root);
            root = arr;
        }
        if (!root.isArray()) return 0;

        long total = 0;
        for (JsonNode element : root) {
            if (!element.isObject()) continue;
            long perElement = (mode == CsvMode.CROSS_JOIN)
                  ? estimateCrossJoinRows(element)
                  : estimateFlatFirstRows(element);
            total = saturatingAdd(total, perElement);
        }
        return total;
    }

    private long estimateFlatFirstRows(JsonNode obj) {
        // Only the first array-of-objects contributes extra rows; each object
        // element of that array becomes one row candidate.
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            if (e.getValue().isArray() && hasObjectElements(e.getValue())) {
                long count = 0;
                for (JsonNode item : e.getValue())
                    if (item.isObject()) count++;
                return Math.max(1, count);
            }
        }
        return 1;
    }

    private long estimateCrossJoinRows(JsonNode obj) {
        long product = 1;
        for (Map.Entry<String, JsonNode> entry : obj.properties()) {
            JsonNode val = entry.getValue();
            if (val.isObject()) {
                product = saturatingMul(product, estimateCrossJoinRows(val));
            } else if (val.isArray() && hasObjectElements(val)) {
                long candidates = 0;
                for (JsonNode item : val)
                    candidates = saturatingAdd(candidates,
                          item.isObject() ? estimateCrossJoinRows(item) : 1);
                // An empty candidate list leaves the current row set unchanged.
                if (candidates > 0) product = saturatingMul(product, candidates);
            }
        }
        return product;
    }

    private long saturatingMul(long a, long b) {
        long r = a * b;
        if (a != 0 && (r / a != b || r > ESTIMATE_CAP)) return ESTIMATE_CAP;
        return Math.min(r, ESTIMATE_CAP);
    }

    private long saturatingAdd(long a, long b) {
        long r = a + b;
        return (r < 0 || r > ESTIMATE_CAP) ? ESTIMATE_CAP : r;
    }

    // ── FLAT_FIRST ────────────────────────────────────────────────────────────

    private List<Map<String, String>> expandFlatFirst(JsonNode obj, String prefix) {
        String firstArrayField = null;
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            if (e.getValue().isArray() && hasObjectElements(e.getValue())) {
                firstArrayField = e.getKey();
                break;
            }
        }

        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());

        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            String   key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode val = e.getValue();

            if (val.isObject()) {
                Map<String, String> flat = new LinkedHashMap<>();
                flattenToCells(val, key, flat);
                for (Map<String, String> row : result) row.putAll(flat);

            } else if (val.isArray() && hasObjectElements(val)) {
                if (e.getKey().equals(firstArrayField)) {
                    List<Map<String, String>> candidates = new ArrayList<>();
                    for (JsonNode item : val) {
                        if (item.isObject()) {
                            Map<String, String> flat = new LinkedHashMap<>();
                            flattenToCells(item, key, flat);
                            candidates.add(flat);
                        }
                    }
                    result = crossJoin(result, candidates);
                } else {
                    for (Map<String, String> row : result) row.put(key, val.toString());
                }

            } else if (val.isArray()) {
                StringBuilder cell = new StringBuilder();
                for (int i = 0; i < val.size(); i++) {
                    if (i > 0) cell.append(",");
                    cell.append(val.get(i).isNull() ? "" : val.get(i).asText());
                }
                for (Map<String, String> row : result) row.put(key, cell.toString());

            } else {
                String text = val.isNull() ? "" : val.asText();
                for (Map<String, String> row : result) row.put(key, text);
            }
        }
        return result;
    }

    // ── CROSS_JOIN ────────────────────────────────────────────────────────────

    private List<Map<String, String>> expandCrossJoin(JsonNode obj, String prefix) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());

        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            String   key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode val = e.getValue();

            if (val.isObject()) {
                result = crossJoin(result, expandCrossJoin(val, key));

            } else if (val.isArray() && hasObjectElements(val)) {
                List<Map<String, String>> candidates = new ArrayList<>();
                for (JsonNode item : val) {
                    if (item.isObject())
                        candidates.addAll(expandCrossJoin(item, key));
                    else {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put(key, item.isNull() ? "" : item.asText());
                        candidates.add(m);
                    }
                }
                result = crossJoin(result, candidates);

            } else if (val.isArray()) {
                StringBuilder cell = new StringBuilder();
                for (int i = 0; i < val.size(); i++) {
                    if (i > 0) cell.append(",");
                    cell.append(val.get(i).isNull() ? "" : val.get(i).asText());
                }
                for (Map<String, String> row : result) row.put(key, cell.toString());

            } else {
                String text = val.isNull() ? "" : val.asText();
                for (Map<String, String> row : result) row.put(key, text);
            }
        }
        return result;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private List<Map<String, String>> crossJoin(List<Map<String, String>> left,
          List<Map<String, String>> right) {
        if (right.isEmpty()) return left;
        List<Map<String, String>> product = new ArrayList<>();
        for (Map<String, String> l : left)
            for (Map<String, String> r : right) {
                Map<String, String> merged = new LinkedHashMap<>(l);
                merged.putAll(r);
                product.add(merged);
            }
        return product;
    }

    private void flattenToCells(JsonNode node, String prefix, Map<String, String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flattenToCells(e.getValue(),
                      prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out);
            }
        } else if (node.isArray()) {
            if (hasObjectElements(node)) {
                out.put(prefix, node.toString());
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(node.get(i).isNull() ? "" : node.get(i).asText());
                }
                out.put(prefix, sb.toString());
            }
        } else {
            out.put(prefix, node.isNull() ? "" : node.asText());
        }
    }

    private boolean hasObjectElements(JsonNode array) {
        for (JsonNode item : array)
            if (item.isObject() || item.isArray()) return true;
        return false;
    }
}