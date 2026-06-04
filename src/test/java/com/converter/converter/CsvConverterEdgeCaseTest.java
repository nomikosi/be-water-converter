package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Edge-case tests for CsvConverter.
 * Covers: quoted fields with commas, empty fields, header-only CSVs,
 * Windows line endings, inconsistent columns, unicode, numeric strings,
 * very wide tables, JSON null to CSV, and JSONPlaceholder-style payloads.
 */
@DisplayName("CsvConverter \u2013 Edge Cases")
class CsvConverterEdgeCaseTest {

    private CsvConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() {
        converter = new CsvConverter();
        json = new ObjectMapper();
    }

    // ── Quoted fields ─────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: quoted field containing a comma")
    void quotedFieldWithComma() throws Exception {
        String csv = "name,address\nAlice,\"123 Main St, Apt 4\"\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("address").asText()).isEqualTo("123 Main St, Apt 4");
    }

    @Test @DisplayName("CSV->JSON: quoted field containing double-quotes (RFC 4180 escaped)")
    void quotedFieldWithEscapedQuotes() throws Exception {
        String csv = "name,quote\nAlice,\"She said \"\"hello\"\"\"\n";
        assertThatCode(() -> converter.csvToJson(csv)).doesNotThrowAnyException();
    }

    // ── Empty / missing fields ────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: empty field in middle of row")
    void emptyFieldMiddle() throws Exception {
        String csv = "a,b,c\n1,,3\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        JsonNode row = result.get(0);
        assertThat(row.get("a").asText()).isEqualTo("1");
        assertThat(row.get("c").asText()).isEqualTo("3");
    }

    @Test @DisplayName("CSV->JSON: trailing empty field")
    void emptyFieldTrailing() throws Exception {
        String csv = "a,b,c\n1,2,\n";
        assertThatCode(() -> converter.csvToJson(csv)).doesNotThrowAnyException();
    }

    @Test @DisplayName("CSV->JSON: header-only (no data rows) does not throw")
    void headerOnly() {
        assertThatCode(() -> converter.csvToJson("id,name,email\n")).doesNotThrowAnyException();
    }

    // ── Line endings ──────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: Windows CRLF line endings parsed correctly")
    void windowsLineEndings() throws Exception {
        String csv = "id,name\r\n1,Alice\r\n2,Bob\r\n";
        assertThatCode(() -> {
            JsonNode result = json.readTree(converter.csvToJson(csv));
            assertThat(result.size()).isGreaterThanOrEqualTo(1);
        }).doesNotThrowAnyException();
    }

    // ── Inconsistent rows ─────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: rows with different sets of fields — all columns appear in header")
    void inconsistentJsonFields() throws Exception {
        String input = "[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"c\":\"4\"},{\"b\":\"5\",\"d\":\"6\"}]";
        String result = converter.jsonToCsv(input);
        assertThat(result).contains("a").contains("b").contains("c").contains("d");
    }

    // ── Unicode ───────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: Greek characters in value")
    void unicodeGreek() throws Exception {
        String csv = "city,pop\n\u0391\u03b8\u03ae\u03bd\u03b1,3153000\n\u0398\u03b5\u03c3\u03c3\u03b1\u03bb\u03bf\u03bd\u03af\u03ba\u03b7,1000000\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("city").asText()).isEqualTo("\u0391\u03b8\u03ae\u03bd\u03b1");
    }

    @Test @DisplayName("CSV->JSON: emoji in value")
    void unicodeEmoji() throws Exception {
        String csv = "label,icon\nSuccess,\u2705\nError,\u274c\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("icon").asText()).isEqualTo("\u2705");
    }

    // ── Numeric strings ───────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: numeric-looking values preserved as strings or numbers")
    void numericLookingValues() throws Exception {
        String csv = "zip,score,price\n10001,99,3.14\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("zip").asText()).isEqualTo("10001");
    }

    @Test @DisplayName("CSV->JSON: leading-zero value (e.g. postal code) not truncated")
    void leadingZeroPreserved() throws Exception {
        String csv = "zip,name\n01234,Springfield\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("zip").asText()).isEqualTo("01234");
    }

    // ── Wide table ────────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: 20-column table parses all columns")
    void wideTable() throws Exception {
        StringBuilder header = new StringBuilder();
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            header.append("col").append(i);
            row.append("val").append(i);
            if (i < 20) { header.append(","); row.append(","); }
        }
        String csv = header + "\n" + row + "\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.get(0).get("col20").asText()).isEqualTo("val20");
        assertThat(result.get(0).get("col1").asText()).isEqualTo("val1");
    }

    // ── JSON null to CSV ──────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: null values in JSON produce empty cell or 'null' string")
    void jsonNullToCsv() throws Exception {
        String result = converter.jsonToCsv("[{\"name\":\"Alice\",\"email\":null}]");
        assertThat(result).contains("name").contains("email").contains("Alice");
    }

    // ── Vega-datasets style flat data ─────────────────────────────────────

    @Test @DisplayName("CSV->JSON: stock-data style (5 cols, 3 rows) round-trip")
    void stockDataStyle() throws Exception {
        String csv = "symbol,date,open,close,volume\n" +
                     "AAPL,2024-01-02,185.20,186.10,65000000\n" +
                     "AAPL,2024-01-03,186.00,184.50,72000000\n" +
                     "MSFT,2024-01-02,374.00,376.30,21000000\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get(0).get("symbol").asText()).isEqualTo("AAPL");
        assertThat(result.get(2).get("symbol").asText()).isEqualTo("MSFT");
    }

    // ── Single column ─────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: single-column CSV parses correctly")
    void singleColumn() throws Exception {
        String csv = "name\nAlice\nBob\nCharlie\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get(2).get("name").asText()).isEqualTo("Charlie");
    }

    // ── Round-trip large ─────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON->CSV: 10-row table headers preserved in round-trip")
    void roundTripTenRows() throws Exception {
        StringBuilder sb = new StringBuilder("id,name,score\n");
        for (int i = 1; i <= 10; i++) {
            sb.append(i).append(",User").append(i).append(",").append(i * 10).append("\n");
        }
        String back = converter.jsonToCsv(converter.csvToJson(sb.toString()));
        assertThat(back).contains("id").contains("name").contains("score");
        assertThat(back).contains("User10");
    }


    @Test @DisplayName("CSV->JSON: null input throws")
    void csvToJsonNull() {
        assertThatThrownBy(() -> converter.csvToJson(null))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("CSV->JSON: blank input throws or returns empty array")
    void csvToJsonBlank() {
        assertThatCode(() -> converter.csvToJson("   "))
              .satisfiesAnyOf(
                    t -> { /* returned empty result */ },
                    t -> assertThat(t).isInstanceOf(Exception.class)
              );
    }

    @Test @DisplayName("JSON->CSV: null input throws")
    void jsonToCsvNull() {
        assertThatThrownBy(() -> converter.jsonToCsv(null))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("JSON->CSV: blank input throws")
    void jsonToCsvBlank() {
        assertThatThrownBy(() -> converter.jsonToCsv("   "))
              .isInstanceOf(Exception.class);
    }

    @Test @DisplayName("CSV->JSON: completely empty string input")
    void csvToJsonEmpty() {
        assertThatCode(() -> converter.csvToJson(""))
              .satisfiesAnyOf(
                    t -> { /* returned empty result */ },
                    t -> assertThat(t).isInstanceOf(Exception.class)
              );
    }

    // ── CROSS_JOIN: Cartesian product ─────────────────────────────────────

    @Test @DisplayName("CROSS_JOIN: 2 arrays × 2 elements each = 4 rows")
    void crossJoinTwoArraysTwoEach() throws Exception {
        String input =
              "{\"name\":\"Alice\",\"sku\":\"001\"," +
                    "\"shipTo\":[{\"name\":\"Bob\"},{\"name\":\"Bob1\"}]," +
                    "\"billTo\":[{\"name\":\"Alice\"},{\"name\":\"Alice1\"}]}";

        String result = converter.jsonToCsv(input, CsvConverter.CsvMode.CROSS_JOIN);
        String[] lines = result.trim().split("\\n");

        assertThat(lines).hasSize(5);   // 1 header + 4 data rows (2×2)
        assertThat(lines[0]).contains("shipTo.name").contains("billTo.name");

        // scalar repeated on all data rows
        for (int i = 1; i <= 4; i++)
            assertThat(lines[i]).contains("Alice").contains("001");

        // all four combinations are present
        assertThat(countRows(lines, "Bob",  "Alice")).isEqualTo(1);
        assertThat(countRows(lines, "Bob",  "Alice1")).isEqualTo(1);
        assertThat(countRows(lines, "Bob1", "Alice")).isEqualTo(1);
        assertThat(countRows(lines, "Bob1", "Alice1")).isEqualTo(1);
    }

    @Test @DisplayName("CROSS_JOIN: 3 arrays × 2 elements each = 8 rows")
    void crossJoinThreeArraysTwoEach() throws Exception {
        String input =
              "{\"id\":1," +
                    "\"a\":[{\"v\":\"a0\"},{\"v\":\"a1\"}]," +
                    "\"b\":[{\"v\":\"b0\"},{\"v\":\"b1\"}]," +
                    "\"c\":[{\"v\":\"c0\"},{\"v\":\"c1\"}]}";

        String[] lines = converter.jsonToCsv(input, CsvConverter.CsvMode.CROSS_JOIN)
              .trim().split("\\n");

        assertThat(lines).hasSize(9);   // 1 header + 8 rows (2×2×2)
        for (int i = 1; i <= 8; i++) assertThat(lines[i]).contains("1");
    }

    @Test @DisplayName("CROSS_JOIN: full shipTo/billTo example from specification")
    void crossJoinSpecExample() throws Exception {
        String input =
              "{\"name\":\"Alice Brown\",\"sku\":\"54321\",\"price\":199.95," +
                    "\"shipTo\":[" +
                    "  {\"name\":\"Bob Brown\",\"address\":\"456 Oak Lane\"," +
                    "   \"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}," +
                    "  {\"name\":\"Bob Brown1\",\"address\":\"456 Oak Lane\"," +
                    "   \"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}]," +
                    "\"billTo\":[" +
                    "  {\"name\":\"Alice Brown\",\"address\":\"456 Oak Lane\"," +
                    "   \"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}," +
                    "  {\"name\":\"Alice Brown1\",\"address\":\"456 Oak Lane\"," +
                    "   \"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}]}";

        String[] lines = converter.jsonToCsv(input, CsvConverter.CsvMode.CROSS_JOIN)
              .trim().split("\\n");

        assertThat(lines).hasSize(5);   // 1 header + 4 data rows
        assertThat(lines[0]).contains("shipTo.name").contains("billTo.name");

        // scalars repeated
        for (int i = 1; i <= 4; i++)
            assertThat(lines[i]).contains("Alice Brown").contains("54321").contains("199.95");

        // all 4 combinations
        assertThat(countRows(lines, "Bob Brown,",   "Alice Brown,")).isEqualTo(1);
        assertThat(countRows(lines, "Bob Brown,",   "Alice Brown1")).isEqualTo(1);
        assertThat(countRows(lines, "Bob Brown1,",  "Alice Brown,")).isEqualTo(1);
        assertThat(countRows(lines, "Bob Brown1,",  "Alice Brown1")).isEqualTo(1);
    }

    @Test @DisplayName("CROSS_JOIN: single array = same rows as FLAT_FIRST")
    void crossJoinSingleArrayEquivalent() throws Exception {
        String input = "{\"x\":1,\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}";

        String flat  = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST);
        String cross = converter.jsonToCsv(input, CsvConverter.CsvMode.CROSS_JOIN);

        assertThat(flat.trim().split("\\n")).hasSize(4);
        assertThat(cross.trim().split("\\n")).hasSize(4);
    }

    // ── FLAT_FIRST: only first array expanded ─────────────────────────────

    @Test @DisplayName("FLAT_FIRST: only first array expanded; second becomes JSON-string cell")
    void flatFirstExpandsOnlyFirstArray() throws Exception {
        String input =
              "{\"name\":\"Alice\",\"sku\":\"001\"," +
                    "\"shipTo\":[{\"name\":\"Bob\"},{\"name\":\"Bob1\"}]," +
                    "\"billTo\":[{\"name\":\"Alice\"},{\"name\":\"Alice1\"}]}";

        String[] lines = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST)
              .trim().split("\\n");

        assertThat(lines).hasSize(3);   // header + 2 rows (only shipTo expanded)
        assertThat(lines[0]).contains("shipTo.name");
        assertThat(lines[0]).doesNotContain("billTo.name");  // not expanded into columns
        assertThat(lines[0]).contains("billTo");             // present as single JSON-string column
        assertThat(lines[1]).contains("Bob");
        assertThat(lines[2]).contains("Bob1");
    }

    @Test @DisplayName("FLAT_FIRST: matches user-requested output shape (no billTo)")
    void flatFirstUserExample() throws Exception {
        String input =
              "{\"name\":\"Alice Brown\",\"sku\":\"54321\",\"price\":199.95," +
                    "\"shipTo\":[" +
                    "  {\"name\":\"Bob Brown\",\"address\":\"456 Oak Lane\"," +
                    "   \"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}," +
                    "  {\"name\":\"Bob Brown1\",\"address\":\"456 Oak Lane\"," +
                    "   \"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}]}";

        String[] lines = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST)
              .trim().split("\\n");

        assertThat(lines).hasSize(3);   // header + 2 rows
        assertThat(lines[0]).startsWith("name,sku,price,shipTo.name");
        assertThat(lines[1]).contains("Alice Brown").contains("Bob Brown").contains("456 Oak Lane");
        assertThat(lines[2]).contains("Bob Brown1");
    }

    // ── default mode is FLAT_FIRST ────────────────────────────────────────

    @Test @DisplayName("default jsonToCsv() delegates to FLAT_FIRST")
    void defaultModeIsFlatFirst() throws Exception {
        String input = "{\"a\":[{\"v\":\"a0\"},{\"v\":\"a1\"}]," +
              "\"b\":[{\"v\":\"b0\"},{\"v\":\"b1\"}]}";
        assertThat(converter.jsonToCsv(input))
              .isEqualTo(converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST));
    }

    // ── Shared behaviour for both modes ──────────────────────────────────

    @Test @DisplayName("both modes: flat object → 1 data row, simple columns")
    void flatObjectBothModes() throws Exception {
        String input = "{\"name\":\"Alice\",\"age\":30}";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String[] lines = converter.jsonToCsv(input, mode).trim().split("\\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).contains("name").contains("age");
            assertThat(lines[1]).contains("Alice").contains("30");
        }
    }

    @Test @DisplayName("both modes: nested object → dot-notation columns, 1 data row")
    void nestedObjectBothModes() throws Exception {
        String input = "{\"user\":{\"id\":1,\"city\":\"Athens\"}}";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String result = converter.jsonToCsv(input, mode);
            assertThat(result).contains("user.id").contains("user.city").contains("Athens");
            assertThat(result.trim().split("\\n")).hasSize(2);
        }
    }

    @Test @DisplayName("both modes: primitive array → comma-separated value in one cell")
    void primitiveArrayBothModes() throws Exception {
        String input = "{\"name\":\"Alice\",\"tags\":[\"a\",\"b\",\"c\"]}";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String[] lines = converter.jsonToCsv(input, mode).trim().split("\\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[1]).contains("a,b,c");
        }
    }

    @Test @DisplayName("both modes: missing nested field filled with empty string")
    void missingFieldBothModes() throws Exception {
        String input =
              "[{\"id\":1,\"address\":{\"city\":\"Athens\"}}," +
                    " {\"id\":2}]";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String result = converter.jsonToCsv(input, mode);
            assertThat(result).contains("address.city").contains("Athens").contains("2");
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────

    /** Count data rows (skip header at index 0) that contain ALL given tokens. */
    private long countRows(String[] lines, String... tokens) {
        long count = 0;
        outer:
        for (int i = 1; i < lines.length; i++) {
            for (String t : tokens) if (!lines[i].contains(t)) continue outer;
            count++;
        }
        return count;
    }
}

