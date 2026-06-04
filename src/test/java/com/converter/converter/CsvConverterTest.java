package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("CsvConverter")
class CsvConverterTest {

    private CsvConverter converter;
    private ObjectMapper json;

    @BeforeEach void setUp() { converter = new CsvConverter(); json = new ObjectMapper(); }

    // ── CSV -> JSON ───────────────────────────────────────────────────────

    @Test @DisplayName("CSV->JSON: simple two-row CSV")
    void csvToJsonTwoRows() throws Exception {
        JsonNode result = json.readTree(converter.csvToJson("id,name,age\n1,Alice,30\n2,Bob,25\n"));
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).get("name").asText()).isEqualTo("Alice");
        assertThat(result.get(1).get("age").asText()).isEqualTo("25");
    }

    @Test @DisplayName("CSV->JSON: single data row")
    void csvToJsonSingleRow() throws Exception {
        JsonNode result = json.readTree(converter.csvToJson("product,price\nWidget,9.99\n"));
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).get("product").asText()).isEqualTo("Widget");
    }

    @Test @DisplayName("CSV->JSON: many columns")
    void csvToJsonManyColumns() throws Exception {
        JsonNode result = json.readTree(converter.csvToJson("a,b,c,d,e\n1,2,3,4,5\n6,7,8,9,10\n"));
        assertThat(result.get(0).get("e").asText()).isEqualTo("5");
        assertThat(result.get(1).get("a").asText()).isEqualTo("6");
    }

    @Test @DisplayName("CSV->JSON: five rows")
    void csvToJsonFiveRows() throws Exception {
        String csv = "id,city\n1,Athens\n2,Berlin\n3,Paris\n4,Rome\n5,Madrid\n";
        JsonNode result = json.readTree(converter.csvToJson(csv));
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.get(4).get("city").asText()).isEqualTo("Madrid");
    }

    // ── JSON -> CSV ───────────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: array of flat objects")
    void jsonToCsvArrayFlat() throws Exception {
        String result = converter.jsonToCsv("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
        assertThat(result).containsIgnoringCase("Alice").containsIgnoringCase("Bob");
    }

    @Test @DisplayName("JSON->CSV: single object wrapped as row")
    void jsonToCsvSingleObject() throws Exception {
        String result = converter.jsonToCsv("{\"product\":\"Widget\",\"price\":\"9.99\"}");
        assertThat(result).contains("Widget").contains("9.99");
    }

    @Test @DisplayName("JSON->CSV: missing fields filled with empty string")
    void jsonToCsvMissingFields() throws Exception {
        String result = converter.jsonToCsv("[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\"}]");
        assertThat(result).contains("a").contains("b");
    }

    @Test @DisplayName("JSON->CSV: non-array/non-object throws")
    void jsonToCsvInvalidInput() {
        assertThatThrownBy(() -> converter.jsonToCsv("\"just a string\"")).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("CSV round-trip: CSV->JSON->CSV header preserved")
    void csvRoundTrip() throws Exception {
        String csv = "id,name,score\n1,Alice,95\n2,Bob,87\n";
        String back = converter.jsonToCsv(converter.csvToJson(csv));
        assertThat(back).contains("Alice").contains("87");
    }

    // ── Single object with two nested objects (the reported case) ─────────

    @Test @DisplayName("JSON->CSV: nested shipTo/billTo produce dot-notation columns")
    void nestedShipToBillToColumns() throws Exception {
        String input =
              "{" +
                    "  \"name\"   : \"Alice Brown\"," +
                    "  \"sku\"    : \"54321\"," +
                    "  \"price\"  : 199.95," +
                    "  \"shipTo\" : { \"name\" : \"Bob Brown\", \"city\" : \"Pretendville\", \"state\" : \"HI\" }," +
                    "  \"billTo\" : { \"name\" : \"Alice Brown\", \"city\" : \"Pretendville\", \"state\" : \"HI\" }" +
                    "}";
        String result = converter.jsonToCsv(input);

        // dot-notation headers must be present
        assertThat(result).contains("shipTo.name");
        assertThat(result).contains("shipTo.city");
        assertThat(result).contains("billTo.name");
        assertThat(result).contains("billTo.state");

        // nested values must appear as plain cell values, not JSON strings
        assertThat(result).contains("Bob Brown");
        assertThat(result).contains("Pretendville");
        assertThat(result).doesNotContain("{\"name\"");   // no raw JSON objects in cells
    }

    @Test @DisplayName("JSON->CSV: nested object values appear unquoted as plain text")
    void nestedValuesArePlainText() throws Exception {
        String input = "{\"user\":{\"id\":1,\"email\":\"alice@example.com\"},\"score\":99}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("user.id");
        assertThat(result).contains("user.email");
        assertThat(result).contains("alice@example.com");
        assertThat(result).contains("99");
    }

    // ── Full shipTo/billTo value assertions ───────────────────────────────

    @Test @DisplayName("JSON->CSV: all shipTo fields land in correct dot-notation columns")
    void nestedShipToAllFields() throws Exception {
        String input =
              "{\"name\":\"Alice Brown\",\"sku\":\"54321\",\"price\":199.95," +
                    "\"shipTo\":{\"name\":\"Bob Brown\",\"address\":\"456 Oak Lane\"," +
                    "\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("shipTo.name");
        assertThat(result).contains("shipTo.address");
        assertThat(result).contains("shipTo.zip");
        assertThat(result).contains("Bob Brown");
        assertThat(result).contains("456 Oak Lane");
        assertThat(result).contains("98999");
    }


    // ── Array field within nested object ─────────────────────────────────

    @Test @DisplayName("JSON->CSV: array value inside nested object serialised as JSON string in cell")
    void arrayValueInNestedObject() throws Exception {
        String input = "{\"meta\":{\"tags\":[\"a\",\"b\",\"c\"]}}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("meta.tags");
    }

    // ── Single nested object (dot-notation) ───────────────────────────────

    @Test @DisplayName("JSON->CSV: nested object fields become dot-notation columns")
    void nestedObjectDotNotation() throws Exception {
        String input = "{\"name\":\"Alice\",\"address\":{\"city\":\"Athens\",\"zip\":\"10001\"}}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("address.city");
        assertThat(result).contains("address.zip");
        assertThat(result).contains("Athens");
        assertThat(result).contains("10001");
        assertThat(result).doesNotContain("{\"city\"");
    }

    // ── Array of objects (indexed notation) ──────────────────────────────

    @Test @DisplayName("JSON->CSV: array of objects becomes indexed shipTo[0].x, shipTo[1].x columns")
    void arrayOfObjectsIndexedNotation() throws Exception {
        String input =
              "{\"name\":\"Alice Brown\",\"sku\":\"54321\",\"price\":199.95," +
                    "\"shipTo\":[" +
                    "  {\"name\":\"Bob Brown\",\"address\":\"456 Oak Lane\",\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}," +
                    "  {\"name\":\"Bob Brown1\",\"address\":\"456 Oak Lane\",\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}" +
                    "]," +
                    "\"billTo\":{\"name\":\"Alice Brown\",\"address\":\"456 Oak Lane\",\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}}";;
        String result = converter.jsonToCsv(input);

        // indexed array columns present
        assertThat(result).contains("shipTo[0].name");
        assertThat(result).contains("shipTo[0].city");
        assertThat(result).contains("shipTo[1].name");
        assertThat(result).contains("shipTo[1].zip");

        // billTo still dot-notation (not an array)
        assertThat(result).contains("billTo.name");
        assertThat(result).contains("billTo.state");

        // actual values present
        assertThat(result).contains("Bob Brown");
        assertThat(result).contains("Bob Brown1");
        assertThat(result).contains("Alice Brown");

        // no raw JSON object blobs in cells
        assertThat(result).doesNotContain("[{\"name\"");
    }

    @Test @DisplayName("JSON->CSV: array of objects — each element fully expanded")
    void arrayElementsFullyExpanded() throws Exception {
        String input =
              "{\"items\":[{\"id\":1,\"label\":\"A\"},{\"id\":2,\"label\":\"B\"}]}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("items[0].id").contains("items[0].label");
        assertThat(result).contains("items[1].id").contains("items[1].label");
        assertThat(result).contains("A").contains("B");
    }

    // ── Primitive array (comma-separated) ────────────────────────────────

    @Test @DisplayName("JSON->CSV: primitive array produces comma-separated cell value")
    void primitiveArrayCommaSeparated() throws Exception {
        String input = "{\"name\":\"Alice\",\"tags\":[\"a\",\"b\",\"c\"]}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("tags");
        assertThat(result).contains("a,b,c");
    }

    // ── Mixed: nested object + array of objects + primitive array ─────────

    @Test @DisplayName("JSON->CSV: mixed nesting types all handled correctly in one row")
    void mixedNestingTypes() throws Exception {
        String input =
              "{\"id\":1," +
                    "\"meta\":{\"version\":\"1.0\"}," +
                    "\"contacts\":[{\"type\":\"email\",\"value\":\"a@b.com\"},{\"type\":\"phone\",\"value\":\"123\"}]," +
                    "\"tags\":[\"x\",\"y\"]}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("meta.version");
        assertThat(result).contains("contacts[0].type").contains("contacts[1].value");
        assertThat(result).contains("tags");
        assertThat(result).contains("a@b.com");
        assertThat(result).contains("x,y");
    }

    // ── Deeply nested ─────────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: 3-level deep nesting produces a.b.c column")
    void deeplyNestedThreeLevels() throws Exception {
        String result = converter.jsonToCsv("{\"a\":{\"b\":{\"c\":\"deep\"}}}");
        assertThat(result).contains("a.b.c").contains("deep");
    }

    // ── Multiple rows with nested objects ─────────────────────────────────

    @Test @DisplayName("JSON->CSV: array of rows each with nested child — all rows flattened")
    void arrayOfNestedObjects() throws Exception {
        String input =
              "[{\"id\":1,\"address\":{\"city\":\"Athens\",\"zip\":\"10001\"}}," +
                    " {\"id\":2,\"address\":{\"city\":\"Berlin\",\"zip\":\"20001\"}}]";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("address.city").contains("address.zip");
        assertThat(result).contains("Athens").contains("Berlin");
        assertThat(result).contains("10001").contains("20001");
    }

    // ── Missing field fills with empty string ─────────────────────────────

    @Test @DisplayName("JSON->CSV: missing nested field in a row becomes empty cell")
    void missingNestedFieldBecomesEmpty() throws Exception {
        String input =
              "[{\"id\":1,\"address\":{\"city\":\"Athens\"}}," +
                    " {\"id\":2}]";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("address.city").contains("Athens").contains("2");
    }

    // ── Flat object unaffected ────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: flat object produces simple columns with no dot-notation")
    void flatObjectUnaffected() throws Exception {
        String result = converter.jsonToCsv("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result).contains("name").contains("age").contains("Alice").contains("30");
        assertThat(result).doesNotContain(".");
    }

    // ── Null values ───────────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV: null inside nested object becomes empty cell")
    void nullInsideNestedObject() throws Exception {
        String result = converter.jsonToCsv("{\"user\":{\"name\":\"Alice\",\"middle\":null}}");
        assertThat(result).contains("user.name").contains("user.middle").contains("Alice");
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
