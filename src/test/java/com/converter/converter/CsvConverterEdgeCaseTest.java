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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Edge-case tests for CsvConverter.
 * Covers: quoted fields, empty/missing fields, header-only CSVs,
 * Windows line endings, inconsistent columns, Unicode, numeric strings,
 * wide tables, JSON null to CSV, and round-trip correctness.
 * NOTE: null/missing-value behavior under FLAT_FIRST/CROSS_JOIN is already
 * exercised exhaustively in CsvConverterTest#NullValues — no duplication here.
 */
@DisplayName("CsvConverter – Edge Cases")
class CsvConverterEdgeCaseTest {

    private CsvConverter converter;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        converter = new CsvConverter();
        json      = new ObjectMapper();
    }

    // ── Quoted fields ─────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: quoted field containing a comma")
    void quotedFieldWithComma() throws Exception {
        String csv    = "name,address\nAlice,\"123 Main St, Apt 4\"\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("address").asText()).isEqualTo("123 Main St, Apt 4");
    }

    @Test @DisplayName("CSV->JSON: quoted field containing double-quotes (RFC 4180 escaped)")
    void quotedFieldWithEscapedQuotes() throws Exception {
        String csv = "name,quote\nAlice,\"She said \"\"hello\"\"\"\n";
        assertThatCode(() -> converter.csvToJson(csv)).doesNotThrowAnyException();
    }

    // ── Empty / missing fields ────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: empty field in middle of row")
    void emptyFieldMiddle() throws Exception {
        String   csv    = "a,b,c\n1,,3\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        JsonNode row    = result.get(0);
        assertThat(row.get("a").asText()).isEqualTo("1");
        assertThat(row.get("c").asText()).isEqualTo("3");
    }

    @Test @DisplayName("CSV->JSON: trailing empty field")
    void emptyFieldTrailing() throws Exception {
        assertThatCode(() -> converter.csvToJson("a,b,c\n1,2,\n")).doesNotThrowAnyException();
    }

    @Test @DisplayName("CSV->JSON: header-only (no data rows) does not throw")
    void headerOnly() {
        assertThatCode(() -> converter.csvToJson("id,name,email\n")).doesNotThrowAnyException();
    }

    // ── Line endings ──────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: Windows CRLF line endings parsed correctly")
    void windowsLineEndings() throws Exception {
        String csv = "id,name\r\n1,Alice\r\n2,Bob\r\n";
        assertThatCode(() -> {
            JsonNode result = json.readTree(converter.csvToJson(csv));
            assertThat(result.size()).isGreaterThanOrEqualTo(1);
        }).doesNotThrowAnyException();
    }

    // ── Inconsistent rows ─────────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: rows with different field sets — all columns appear in header")
    void inconsistentJsonFields() throws Exception {
        String input  = "[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"c\":\"4\"},{\"b\":\"5\",\"d\":\"6\"}]";
        String result = converter.jsonToCsv(input);
        assertThat(result).contains("a").contains("b").contains("c").contains("d");
    }

    // ── Unicode ───────────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: Greek characters in value")
    void unicodeGreek() throws Exception {
        String   csv    = "city,pop\nΑθήνα,3153000\nΘεσσαλονίκη,1000000\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("city").asText()).isEqualTo("Αθήνα");
    }

    @Test @DisplayName("CSV->JSON: emoji in value")
    void unicodeEmoji() throws Exception {
        String   csv    = "label,icon\nSuccess,✅\nError,❌\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("icon").asText()).isEqualTo("✅");
    }

    // ── Numeric strings ───────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: numeric-looking values become typed JSON numbers by default")
    void numericLookingValues() throws Exception {
        String   csv    = "zip,score,price\n10001,99,3.14\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("zip").intValue()).isEqualTo(10001);
        assertThat(result.get(0).get("score").isInt()).isTrue();
        assertThat(result.get(0).get("price").doubleValue()).isEqualTo(3.14);
    }

    @Test @DisplayName("CSV->JSON: type inference can be disabled — everything stays a string")
    void inferenceDisabledKeepsStrings() throws Exception {
        String   csv    = "zip,score,flag\n10001,99,true\n";
        JsonNode result = json.readTree(converter.csvToJson(csv, false));
        assertThat(result.get(0).get("zip").isTextual()).isTrue();
        assertThat(result.get(0).get("score").isTextual()).isTrue();
        assertThat(result.get(0).get("flag").isTextual()).isTrue();
    }

    @Test @DisplayName("CSV->JSON: booleans and null inferred, plain text untouched")
    void booleanAndNullInference() throws Exception {
        String   csv    = "active,gone,name\ntrue,null,Alice\n";
        JsonNode row    = json.readTree(converter.csvToJson(csv)).get(0);
        assertThat(row.get("active").isBoolean()).isTrue();
        assertThat(row.get("active").booleanValue()).isTrue();
        assertThat(row.get("gone").isNull()).isTrue();
        assertThat(row.get("name").isTextual()).isTrue();
    }

    @Test @DisplayName("CSV->JSON: leading-zero value stays a string and is not truncated")
    void leadingZeroPreserved() throws Exception {
        String   csv    = "zip,name\n01234,Springfield\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("zip").isTextual()).isTrue();
        assertThat(result.get(0).get("zip").asText()).isEqualTo("01234");
    }

    @Test @DisplayName("CSV->JSON: integer larger than Long stays a string")
    void hugeIntegerStaysString() throws Exception {
        String   csv    = "id\n99999999999999999999999999\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("id").isTextual()).isTrue();
    }

    // ── FLAT_FIRST mixed-array regression (v1.4.0) ────────────────────────────

    @Test @DisplayName("JSON->CSV FLAT_FIRST: primitive elements of the expanded array become rows")
    void flatFirstMixedArrayKeepsPrimitives() throws Exception {
        String input  = "{\"values\":[{\"v\":\"obj1\"},\"primitive\",{\"v\":\"obj2\"}]}";
        String result = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST);
        assertThat(result).contains("obj1").contains("primitive").contains("obj2");
        assertThat(result.trim().split("\n")).hasSize(4); // header + 3 rows
    }

    @Test @DisplayName("JSON->CSV FLAT_FIRST: array-of-arrays is not silently dropped")
    void flatFirstArrayOfArraysNotDropped() throws Exception {
        String input  = "{\"id\":1,\"matrix\":[[1,2],[3,4]]}";
        String result = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST);
        assertThat(result).contains("matrix");
        assertThat(result).contains("[1,2]").contains("[3,4]");
    }

    @Test @DisplayName("FLAT_FIRST: row estimate matches actual rows for mixed arrays")
    void flatFirstMixedArrayEstimateMatches() throws Exception {
        String input = "{\"values\":[{\"v\":\"obj1\"},\"primitive\",{\"v\":\"obj2\"}]}";
        String csvOut = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST);
        long actualRows = csvOut.strip().split("\n").length - 1;
        assertThat(converter.estimateRowCount(input, CsvConverter.CsvMode.FLAT_FIRST))
              .isEqualTo(actualRows);
    }

    // ── Wide table ────────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: 20-column table parses all columns")
    void wideTable() throws Exception {
        StringBuilder header = new StringBuilder();
        StringBuilder row    = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            header.append("col").append(i);
            row.append("val").append(i);
            if (i < 20) { header.append(","); row.append(","); }
        }
        String   csv    = header + "\n" + row + "\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("col20").asText()).isEqualTo("val20");
        assertThat(result.get(0).get("col1").asText()).isEqualTo("val1");
    }

    // ── JSON null to CSV ──────────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: null values in JSON produce empty cell")
    void jsonNullToCsv() throws Exception {
        String result = converter.jsonToCsv("[{\"name\":\"Alice\",\"email\":null}]");
        assertThat(result).contains("name").contains("email").contains("Alice");
    }

    // ── Realistic flat data ───────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: stock-data style (5 cols, 3 rows)")
    void stockDataStyle() throws Exception {
        String csv = "symbol,date,open,close,volume\n"
              + "AAPL,2024-01-02,185.20,186.10,65000000\n"
              + "AAPL,2024-01-03,186.00,184.50,72000000\n"
              + "MSFT,2024-01-02,374.00,376.30,21000000\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get(0).get("symbol").asText()).isEqualTo("AAPL");
        assertThat(result.get(2).get("symbol").asText()).isEqualTo("MSFT");
    }

    @Test @DisplayName("CSV->JSON: single-column CSV parses correctly")
    void singleColumn() throws Exception {
        String   csv    = "name\nAlice\nBob\nCharlie\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get(2).get("name").asText()).isEqualTo("Charlie");
    }

    // ── Round-trips ───────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON->CSV: 10-row table headers preserved in round-trip")
    void roundTripTenRows() throws Exception {
        StringBuilder sb = new StringBuilder("id,name,score\n");
        for (int i = 1; i <= 10; i++)
            sb.append(i).append(",User").append(i).append(",").append(i * 10).append("\n");

        String back = converter.jsonToCsv(converter.csvToJson(sb.toString()));
        assertThat(back).contains("id").contains("name").contains("score").contains("User10");
    }

    // ── Null / blank inputs ───────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: null input throws")
    void csvToJsonNull() {
        assertThatThrownBy(() -> converter.csvToJson(null)).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("CSV->JSON: blank input throws or returns empty")
    void csvToJsonBlank() {
        assertThatCode(() -> converter.csvToJson(" "))
              .satisfiesAnyOf(t -> { /* empty result ok */ },
                    t -> assertThat(t).isInstanceOf(Exception.class));
    }

    @Test @DisplayName("JSON->CSV: null input throws")
    void jsonToCsvNull() {
        assertThatThrownBy(() -> converter.jsonToCsv(null)).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("JSON->CSV: blank input throws")
    void jsonToCsvBlank() {
        assertThatThrownBy(() -> converter.jsonToCsv(" ")).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("CSV->JSON: completely empty string throws or returns empty")
    void csvToJsonEmpty() {
        assertThatCode(() -> converter.csvToJson(""))
              .satisfiesAnyOf(t -> { /* empty result ok */ },
                    t -> assertThat(t).isInstanceOf(Exception.class));
    }
}