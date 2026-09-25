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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.util.*;
import java.util.concurrent.CancellationException;

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

    /**
     * Delimiter and quote character for reading and writing CSV. Comma-separated
     * is only one convention: semicolon is the norm across much of Europe (where
     * the comma is the decimal separator) and tab-separated files are common too.
     */
    public record CsvFormat(char delimiter, char quote) {
        public static final CsvFormat DEFAULT = new CsvFormat(',', '"');
        public static final CsvFormat SEMICOLON = new CsvFormat(';', '"');
        public static final CsvFormat TAB = new CsvFormat('\t', '"');

        /** The format for a delimiter a document was detected to use, with the standard quote. */
        public static CsvFormat forDelimiter(char delimiter) {
            return switch (delimiter) {
                case ','  -> DEFAULT;
                case ';'  -> SEMICOLON;
                case '\t' -> TAB;
                default   -> new CsvFormat(delimiter, '"');
            };
        }
    }

    private final ObjectMapper jsonMapper;
    private final CsvMapper   csvMapper;

    public CsvConverter() {
        // No INDENT_OUTPUT: this mapper only ever writes the internal JSON
        // pivot, which the next stage re-parses and nobody reads. Indenting it
        // measured 1.29-1.45x the compact size for no benefit.
        jsonMapper = PivotJson.mapper();
        csvMapper  = new CsvMapper();
        // Jackson's default quote check is a fast over-approximation whose result
        // depends on the separator: with ';' it quotes numeric-looking cells that
        // it leaves bare with ','. The strict check quotes only what genuinely
        // needs it, so output is consistent whichever delimiter is chosen.
        csvMapper.enable(com.fasterxml.jackson.dataformat.csv.CsvGenerator.Feature
              .STRICT_CHECK_FOR_QUOTING);
    }

    // ── CSV → JSON ────────────────────────────────────────────────────────────

    /** Convenience overload — infers scalar types by default. */
    public String csvToJson(String csv) throws Exception {
        return csvToJson(csv, true);
    }

    /**
     * Converts CSV to a JSON array of objects. With {@code inferTypes}, cell
     * values that look like integers, decimals, booleans or {@code null} become
     * typed JSON values instead of strings; values with leading zeros stay
     * strings so identifiers like "007" are not mangled.
     */
    public String csvToJson(String csv, boolean inferTypes) throws Exception {
        return csvToJson(csv, inferTypes, CsvFormat.DEFAULT);
    }

    /** @param format delimiter and quote character to parse with. */
    public String csvToJson(String csv, boolean inferTypes, CsvFormat format) throws Exception {
        if (csv == null || csv.isBlank())
            throw new IllegalArgumentException("Input CSV must not be empty");
        List<String[]> lines = readRows(csv, format);
        if (lines.isEmpty()) return jsonMapper.writeValueAsString(jsonMapper.createArrayNode());

        List<String> headers = uniqueHeaders(lines.get(0));

        ArrayNode arr = jsonMapper.createArrayNode();
        for (int r = 1; r < lines.size(); r++) {
            String[] cells = lines.get(r);
            // Extra cells have nowhere to go: the loop below stops at the header
            // count, so they were dropped and Format then wrote the truncated
            // file back over the user's data.
            //
            // Only cells that actually carry something count. A trailing
            // delimiter — the dialect Excel and many exporters emit — leaves an
            // empty cell past the last header, and refusing the whole file over
            // a value that is the empty string helps nobody.
            int discarded = 0;
            for (int c = headers.size(); c < cells.length; c++)
                if (cells[c] != null && !cells[c].isEmpty()) discarded++;
            if (discarded > 0)
                throw new IllegalArgumentException(String.format(
                      "Row %d has %d values but the header declares %d columns, so %d would be "
                      + "discarded. Add the missing header names, or quote the delimiter inside "
                      + "the value if it was meant as text.",
                      r + 1, cells.length, headers.size(), discarded));
            ObjectNode obj = arr.addObject();
            // Ragged rows keep the old behaviour: a missing trailing cell means
            // the key is absent rather than present-and-empty.
            for (int c = 0; c < headers.size() && c < cells.length; c++) {
                String value = cells[c] == null ? "" : cells[c];
                obj.set(headers.get(c), inferTypes
                      ? ScalarInference.infer(value, jsonMapper.getNodeFactory())
                      : jsonMapper.getNodeFactory().textNode(value));
            }
        }
        return jsonMapper.writeValueAsString(arr);
    }

    /**
     * Every row of the document as its cells, header row included, read
     * positionally rather than through withHeader(): letting Jackson key rows by
     * header name silently collapses repeated column names, so a trailing empty
     * duplicate would overwrite the populated column.
     */
    private List<String[]> readRows(String csv, CsvFormat format) throws java.io.IOException {
        // WRAP_AS_ARRAY is what lets a column-less schema read raw rows; without
        // it Jackson enforces the schema's zero columns and rejects every line.
        MappingIterator<String[]> it = csvMapper.readerFor(String[].class)
              .with(CsvParser.Feature.WRAP_AS_ARRAY)
              // A blank line is not a row. Without this it read as one empty
              // cell, so "a,b\n1,2\n\n" — a paste with a trailing blank line —
              // gained a phantom {"a":""} row, and Format wrote it back.
              .with(CsvParser.Feature.SKIP_EMPTY_LINES)
              .with(schemaFor(format))
              .readValues(ConversionPipeline.stripBom(csv));
        return it.readAll();
    }

    private static CsvSchema schemaFor(CsvFormat format) {
        return CsvSchema.emptySchema()
              .withColumnSeparator(format.delimiter())
              .withQuoteChar(format.quote());
    }

    /**
     * Re-lays-out CSV without interpreting it: rows in, the same rows out,
     * normalising only quoting, line endings and blank lines.
     *
     * <p>Format used to go through the JSON pivot, which turns rows into objects
     * keyed by header. That rewrote the header line ({@code id,id,} became
     * {@code id,id_2,column_3}), dropped every header a ragged row happened to
     * miss ({@code a,b,c\n1} came back as {@code a\n1}), and refused a file that
     * was only a header. None of that is layout.
     */
    public String reformat(String csv, CsvFormat format) throws Exception {
        if (csv == null || csv.isBlank())
            throw new IllegalArgumentException("Input CSV must not be empty");
        List<String[]> rows = readRows(csv, format);
        // A column-less schema writes each array positionally, so nothing is
        // named, padded or discarded on the way out.
        return csvMapper.writer(schemaFor(format)).writeValueAsString(rows);
    }

    /**
     * Disambiguates repeated header names ({@code id}, {@code id_2}) so no column
     * is lost, and names anonymous columns so an empty header cell is still
     * addressable.
     */
    private List<String> uniqueHeaders(String[] rawHeaders) {
        List<String> headers = new ArrayList<>(rawHeaders.length);
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < rawHeaders.length; i++) {
            String name = rawHeaders[i] == null ? "" : rawHeaders[i].trim();
            if (name.isEmpty()) name = "column_" + (i + 1);
            if (!used.add(name)) {
                int n = 2;
                while (!used.add(name + "_" + n)) n++;
                name = name + "_" + n;
            }
            headers.add(name);
        }
        return headers;
    }

    // ── JSON → CSV (mode-aware) ───────────────────────────────────────────────

    /** Convenience overload – defaults to FLAT_FIRST for backwards compatibility. */
    public String jsonToCsv(String json) throws Exception {
        return jsonToCsv(json, CsvMode.FLAT_FIRST);
    }

    public String jsonToCsv(String json, CsvMode mode) throws Exception {
        return jsonToCsv(jsonMapper.readTree(json), mode);
    }

    /** Overload for callers that already hold a parsed tree (avoids re-parsing). */
    public String jsonToCsv(JsonNode root, CsvMode mode) throws Exception {
        return jsonToCsv(root, mode, CsvFormat.DEFAULT);
    }

    /** @param format delimiter and quote character to write with. */
    public String jsonToCsv(JsonNode root, CsvMode mode, CsvFormat format) throws Exception {
        // Normalise: wrap a bare object in a single-element array
        if (root.isObject()) {
            ArrayNode arr = jsonMapper.createArrayNode();
            arr.add(root);
            root = arr;
        }

        if (!root.isArray())
            throw new IllegalArgumentException(
                  "JSON must be an array of objects or a single object for CSV output");

        // A CSV row is an object's fields, so an element that is not an object
        // has no row to become. Dropping them returned "" for [1,2,3] and the
        // panel reported that as a successful conversion into a blank pane.
        int nonObjects = 0;
        for (JsonNode element : root) if (!element.isObject()) nonObjects++;
        if (nonObjects > 0)
            throw new IllegalArgumentException(nonObjects == root.size()
                  ? "CSV rows come from objects, and no element of this array is one. "
                        + "An array of values has no columns to write."
                  : nonObjects + " of the " + root.size() + " elements are not objects, so they "
                        + "have no row to become. Wrap each value in an object first.");

        // Expand every top-level element according to the chosen mode
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode element : root) {
            checkInterrupted();
            List<Map<String, String>> expanded =
                  (mode == CsvMode.CROSS_JOIN)
                        ? expandCrossJoin(element, "")
                        : expandFlatFirst(element, "");
            rows.addAll(expanded);
        }

        // Returning "" here reported an empty document as a successful
        // conversion. An empty root array is the only way to reach this now.
        if (rows.isEmpty())
            throw new IllegalArgumentException(
                  "Nothing to write: the input has no rows to turn into CSV.");

        // Collect ordered headers (insertion order from first row, then rest)
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (Map<String, String> row : rows) headers.addAll(row.keySet());

        // Rows can exist while contributing no columns at all ([{}], or a filter
        // that narrows to empty objects). Jackson's own message for that case
        // leaks its internals, so say what actually happened.
        if (headers.isEmpty())
            throw new IllegalArgumentException(
                  "Input has no columns to write: the objects being converted are empty.");

        CsvSchema.Builder sb = CsvSchema.builder().setUseHeader(true)
              .setColumnSeparator(format.delimiter())
              .setQuoteChar(format.quote());
        for (String h : headers) sb.addColumn(h);

        // The schema drives column order and emits an empty cell for any header a
        // row is missing, so padding every row into a second list here would only
        // double peak memory on exactly the largest conversions.
        return csvMapper.writer(sb.build()).writeValueAsString(rows);
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
        return estimateRowCount(jsonMapper.readTree(json), mode);
    }

    /** Overload for callers that already hold a parsed tree (avoids re-parsing). */
    public long estimateRowCount(JsonNode root, CsvMode mode) {
        if (root.isObject()) {
            ArrayNode arr = jsonMapper.createArrayNode();
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
        // Only the first container-array contributes extra rows; every element
        // of that array (object or not) becomes one row candidate.
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            if (e.getValue().isArray() && hasObjectElements(e.getValue())) {
                return Math.max(1, e.getValue().size());
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
        checkInterrupted();
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
                for (Map<String, String> row : result) putAllCells(row, flat);

            } else if (val.isArray() && hasObjectElements(val)) {
                if (e.getKey().equals(firstArrayField)) {
                    List<Map<String, String>> candidates = new ArrayList<>();
                    for (JsonNode item : val) {
                        if (item.isObject()) {
                            Map<String, String> flat = new LinkedHashMap<>();
                            flattenToCells(item, key, flat);
                            candidates.add(flat);
                        } else {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put(key, cellValue(item));
                            candidates.add(m);
                        }
                    }
                    result = crossJoin(result, candidates);
                } else {
                    putInAllRows(result, key, val.toString());
                }

            } else {
                putInAllRows(result, key, scalarCell(val));
            }
        }
        return result;
    }

    // ── CROSS_JOIN ────────────────────────────────────────────────────────────

    private List<Map<String, String>> expandCrossJoin(JsonNode obj, String prefix) {
        checkInterrupted();
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
                        m.put(key, cellValue(item));
                        candidates.add(m);
                    }
                }
                result = crossJoin(result, candidates);

            } else {
                putInAllRows(result, key, scalarCell(val));
            }
        }
        return result;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private List<Map<String, String>> crossJoin(List<Map<String, String>> left,
          List<Map<String, String>> right) {
        if (right.isEmpty()) return left;
        checkInterrupted();
        List<Map<String, String>> product = new ArrayList<>();
        for (Map<String, String> l : left) {
            // A single join can itself be the runaway step, so poll per outer row
            // rather than only on entry.
            checkInterrupted();
            for (Map<String, String> r : right) {
                Map<String, String> merged = new LinkedHashMap<>(l);
                putAllCells(merged, r);
                product.add(merged);
            }
        }
        return product;
    }

    /**
     * Writes one cell, refusing to overwrite a cell already in the row.
     *
     * <p>Columns are named by joining nested keys with dots, and a key may
     * itself contain a dot, so {@code {"a.b":1,"a":{"b":2}}} produced ONE column
     * {@code a.b} holding whichever value was written last. The other was gone,
     * and nothing said so. Every cell write goes through here, including the
     * merges a cross join does, because the collision can come from any of them.
     */
    private static void putCell(Map<String, String> row, String column, String value) {
        if (row.containsKey(column))
            throw new IllegalArgumentException(
                  "Two keys produce the same CSV column \"" + column + "\": a nested key and a "
                  + "key that already contains a dot flatten to the same name, so one value "
                  + "would overwrite the other. Rename one of them first.");
        row.put(column, value);
    }

    private static void putAllCells(Map<String, String> row, Map<String, String> cells) {
        for (Map.Entry<String, String> cell : cells.entrySet())
            putCell(row, cell.getKey(), cell.getValue());
    }

    /**
     * Row expansion is the unbounded hot path; honouring interruption is what
     * lets the UI's Cancel action stop a runaway conversion.
     */
    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Conversion cancelled");
    }

    private void putInAllRows(List<Map<String, String>> rows, String key, String value) {
        for (Map<String, String> row : rows) putCell(row, key, value);
    }

    /** Renders a non-object value as one cell: scalar arrays join with commas, null becomes "". */
    private String scalarCell(JsonNode val) {
        if (!val.isArray()) return val.isNull() ? "" : val.asText();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < val.size(); i++) {
            if (i > 0) cell.append(",");
            cell.append(val.get(i).isNull() ? "" : val.get(i).asText());
        }
        return cell.toString();
    }

    private void flattenToCells(JsonNode node, String prefix, Map<String, String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flattenToCells(e.getValue(),
                      prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out);
            }
        } else if (node.isArray() && hasObjectElements(node)) {
            putCell(out, prefix, node.toString());
        } else {
            putCell(out, prefix, scalarCell(node));
        }
    }

    /** Single-cell rendering of a leaf value: containers as JSON, null as "". */
    private String cellValue(JsonNode node) {
        if (node.isNull()) return "";
        return node.isContainerNode() ? node.toString() : node.asText();
    }

    private boolean hasObjectElements(JsonNode array) {
        for (JsonNode item : array)
            if (item.isObject() || item.isArray()) return true;
        return false;
    }
}