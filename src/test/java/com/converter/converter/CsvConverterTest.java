package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CsvConverter")
class CsvConverterTest {

    private CsvConverter converter;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        converter = new CsvConverter();
        json      = new ObjectMapper();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Splits CSV text into trimmed, non-blank lines. */
    private List<String> lines(String csv) {
        return Arrays.stream(csv.split("\n"))
              .map(l -> l.replace("\r", ""))
              .filter(l -> !l.isBlank())
              .toList();
    }

    private String jsonToCsv(String input, CsvConverter.CsvMode mode) throws Exception {
        return converter.jsonToCsv(input, mode);
    }

    // ── CSV -> JSON ───────────────────────────────────────────────────────────

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

    // ── JSON -> CSV (no-arg / legacy) ─────────────────────────────────────────

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
        String csv  = "id,name,score\n1,Alice,95\n2,Bob,87\n";
        String back = converter.jsonToCsv(converter.csvToJson(csv));
        assertThat(back).contains("Alice").contains("87");
    }

    // ── Nested plain objects (dot-notation) ───────────────────────────────────

    @Test @DisplayName("JSON->CSV: nested shipTo/billTo produce dot-notation columns")
    void nestedShipToBillToColumns() throws Exception {
        String input = "{"
              + " \"name\" : \"Alice Brown\","
              + " \"sku\" : \"54321\","
              + " \"price\" : 199.95,"
              + " \"shipTo\" : { \"name\" : \"Bob Brown\", \"city\" : \"Pretendville\", \"state\" : \"HI\" },"
              + " \"billTo\" : { \"name\" : \"Alice Brown\", \"city\" : \"Pretendville\", \"state\" : \"HI\" }"
              + "}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("shipTo.name");
        assertThat(result).contains("shipTo.city");
        assertThat(result).contains("billTo.name");
        assertThat(result).contains("billTo.state");
        assertThat(result).contains("Bob Brown");
        assertThat(result).contains("Pretendville");
        assertThat(result).doesNotContain("{\"name\"");
    }

    @Test @DisplayName("JSON->CSV: nested object values appear as plain text (no raw JSON)")
    void nestedValuesArePlainText() throws Exception {
        String input  = "{\"user\":{\"id\":1,\"email\":\"alice@example.com\"},\"score\":99}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("user.id");
        assertThat(result).contains("user.email");
        assertThat(result).contains("alice@example.com");
        assertThat(result).contains("99");
    }

    @Test @DisplayName("JSON->CSV: all shipTo fields land in correct dot-notation columns")
    void nestedShipToAllFields() throws Exception {
        String input = "{\"name\":\"Alice Brown\",\"sku\":\"54321\",\"price\":199.95,"
              + "\"shipTo\":{\"name\":\"Bob Brown\",\"address\":\"456 Oak Lane\","
              + "\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("shipTo.name");
        assertThat(result).contains("shipTo.address");
        assertThat(result).contains("shipTo.zip");
        assertThat(result).contains("Bob Brown");
        assertThat(result).contains("456 Oak Lane");
        assertThat(result).contains("98999");
    }

    @Test @DisplayName("JSON->CSV: array value inside nested object serialised as comma-joined cell")
    void arrayValueInNestedObject() throws Exception {
        String result = converter.jsonToCsv("{\"meta\":{\"tags\":[\"a\",\"b\",\"c\"]}}");
        assertThat(result).contains("meta.tags");
    }

    @Test @DisplayName("JSON->CSV: nested object fields become dot-notation columns")
    void nestedObjectDotNotation() throws Exception {
        String input  = "{\"name\":\"Alice\",\"address\":{\"city\":\"Athens\",\"zip\":\"10001\"}}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("address.city");
        assertThat(result).contains("address.zip");
        assertThat(result).contains("Athens");
        assertThat(result).contains("10001");
        assertThat(result).doesNotContain("{\"city\"");
    }

    @Test @DisplayName("JSON->CSV: 3-level deep nesting produces a.b.c column")
    void deeplyNestedThreeLevels() throws Exception {
        String result = converter.jsonToCsv("{\"a\":{\"b\":{\"c\":\"deep\"}}}");
        assertThat(result).contains("a.b.c").contains("deep");
    }

    @Test @DisplayName("JSON->CSV: array of rows each with nested child — all rows flattened")
    void arrayOfNestedObjects() throws Exception {
        String input = "[{\"id\":1,\"address\":{\"city\":\"Athens\",\"zip\":\"10001\"}},"
              + " {\"id\":2,\"address\":{\"city\":\"Berlin\",\"zip\":\"20001\"}}]";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("address.city").contains("address.zip");
        assertThat(result).contains("Athens").contains("Berlin");
        assertThat(result).contains("10001").contains("20001");
    }

    @Test @DisplayName("JSON->CSV: flat object produces simple columns with no dot-notation")
    void flatObjectUnaffected() throws Exception {
        String result = converter.jsonToCsv("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result).contains("name").contains("age").contains("Alice").contains("30");
        assertThat(result).doesNotContain(".");
    }

    @Test @DisplayName("JSON->CSV: null inside nested object becomes empty cell")
    void nullInsideNestedObject() throws Exception {
        String result = converter.jsonToCsv("{\"user\":{\"name\":\"Alice\",\"middle\":null}}");
        assertThat(result).contains("user.name").contains("user.middle").contains("Alice");
    }

    // ── FLAT_FIRST row-expansion ──────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV FLAT_FIRST: array-of-objects inside bare object expands into rows")
    void arrayOfObjectsExpandedIntoRows() throws Exception {
        String input = "{\"name\":\"Alice Brown\",\"sku\":\"54321\",\"price\":199.95,"
              + "\"shipTo\":["
              + " {\"name\":\"Bob Brown\",\"address\":\"456 Oak Lane\",\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"},"
              + " {\"name\":\"Bob Brown1\",\"address\":\"456 Oak Lane\",\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}"
              + "],"
              + "\"billTo\":{\"name\":\"Alice Brown\",\"address\":\"456 Oak Lane\",\"city\":\"Pretendville\",\"state\":\"HI\",\"zip\":\"98999\"}}";
        String result = converter.jsonToCsv(input); // default = FLAT_FIRST

        assertThat(result).contains("shipTo.name").contains("shipTo.city").contains("shipTo.zip");
        assertThat(result).contains("billTo.name").contains("billTo.state");
        assertThat(result).contains("Bob Brown").contains("Bob Brown1").contains("Alice Brown");
        assertThat(result.trim().split("\n")).hasSize(3); // header + 2 rows
        assertThat(result).doesNotContain("[{\"name\"");
    }

    @Test @DisplayName("JSON->CSV FLAT_FIRST: each array element becomes a row")
    void arrayElementsExpandedIntoRows() throws Exception {
        String input  = "{\"items\":[{\"id\":1,\"label\":\"A\"},{\"id\":2,\"label\":\"B\"}]}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("items.id").contains("items.label");
        assertThat(result).contains("A").contains("B");
        assertThat(result.trim().split("\n")).hasSize(3);
    }

    @Test @DisplayName("JSON->CSV FLAT_FIRST: only first array expanded; second becomes JSON-string cell")
    void flatFirstExpandsOnlyFirstArray() throws Exception {
        String input = "{\"name\":\"Alice\",\"sku\":\"001\","
              + "\"shipTo\":[{\"name\":\"Bob\"},{\"name\":\"Bob1\"}],"
              + "\"billTo\":[{\"name\":\"Alice\"},{\"name\":\"Alice1\"}]}";

        String[] lines = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST).trim().split("\n");

        assertThat(lines).hasSize(3);
        assertThat(lines[0]).contains("shipTo.name");
        assertThat(lines[0]).doesNotContain("billTo.name");
        assertThat(lines[0]).contains("billTo");   // present as single JSON-string column
        assertThat(lines[1]).contains("Bob");
        assertThat(lines[2]).contains("Bob1");
    }

    @Test @DisplayName("JSON->CSV FLAT_FIRST: primitive array produces comma-separated cell")
    void primitiveArrayCommaSeparated() throws Exception {
        String input  = "{\"name\":\"Alice\",\"tags\":[\"a\",\"b\",\"c\"]}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("tags");
        assertThat(result).contains("a,b,c");
    }

    @Test @DisplayName("JSON->CSV FLAT_FIRST: mixed nesting — nested obj dot-flattened, obj-array expanded, prim-array joined")
    void mixedNestingTypes() throws Exception {
        String input = "{\"id\":1,"
              + "\"meta\":{\"version\":\"1.0\"},"
              + "\"contacts\":[{\"type\":\"email\",\"value\":\"a@b.com\"},{\"type\":\"phone\",\"value\":\"123\"}],"
              + "\"tags\":[\"x\",\"y\"]}";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("meta.version");
        assertThat(result).contains("contacts.type").contains("contacts.value");
        assertThat(result).contains("a@b.com");
        assertThat(result).contains("tags").contains("x,y");
        assertThat(result.trim().split("\n")).hasSize(3);
    }

    @Test @DisplayName("JSON->CSV FLAT_FIRST: missing nested field in a row becomes empty cell")
    void missingNestedFieldBecomesEmpty() throws Exception {
        String input  = "[{\"id\":1,\"address\":{\"city\":\"Athens\"}},{\"id\":2}]";
        String result = converter.jsonToCsv(input);

        assertThat(result).contains("address.city").contains("Athens").contains("2");
    }

    // ── CROSS_JOIN ────────────────────────────────────────────────────────────

    @Test @DisplayName("JSON->CSV CROSS_JOIN: 3 arrays × 2 elements each = 8 data rows")
    void crossJoinThreeArraysTwoEach() throws Exception {
        String input = "{\"id\":1,"
              + "\"a\":[{\"v\":\"a0\"},{\"v\":\"a1\"}],"
              + "\"b\":[{\"v\":\"b0\"},{\"v\":\"b1\"}],"
              + "\"c\":[{\"v\":\"c0\"},{\"v\":\"c1\"}]}";

        String[] lines = converter.jsonToCsv(input, CsvConverter.CsvMode.CROSS_JOIN).trim().split("\n");
        assertThat(lines).hasSize(9); // header + 8 rows (2×2×2)
        for (int i = 1; i <= 8; i++) assertThat(lines[i]).contains("1");
    }

    @Test @DisplayName("JSON->CSV CROSS_JOIN: single array = same rows as FLAT_FIRST")
    void crossJoinSingleArrayEquivalent() throws Exception {
        String input = "{\"x\":1,\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}";

        String flat  = converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST);
        String cross = converter.jsonToCsv(input, CsvConverter.CsvMode.CROSS_JOIN);

        assertThat(flat.trim().split("\n")).hasSize(4);
        assertThat(cross.trim().split("\n")).hasSize(4);
    }

    // ── Default overload ──────────────────────────────────────────────────────

    @Test @DisplayName("default jsonToCsv() delegates to FLAT_FIRST")
    void defaultModeIsFlatFirst() throws Exception {
        String input = "{\"a\":[{\"v\":\"a0\"},{\"v\":\"a1\"}],\"b\":[{\"v\":\"b0\"},{\"v\":\"b1\"}]}";
        assertThat(converter.jsonToCsv(input))
              .isEqualTo(converter.jsonToCsv(input, CsvConverter.CsvMode.FLAT_FIRST));
    }

    // ── Both modes: shared behaviour ──────────────────────────────────────────

    @Test @DisplayName("both modes: flat object → 1 data row, simple columns")
    void flatObjectBothModes() throws Exception {
        String input = "{\"name\":\"Alice\",\"age\":30}";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String[] lines = converter.jsonToCsv(input, mode).trim().split("\n");
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
            assertThat(result.trim().split("\n")).hasSize(2);
        }
    }

    @Test @DisplayName("both modes: primitive array → comma-separated value in one cell")
    void primitiveArrayBothModes() throws Exception {
        String input = "{\"name\":\"Alice\",\"tags\":[\"a\",\"b\",\"c\"]}";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String[] lines = converter.jsonToCsv(input, mode).trim().split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[1]).contains("a,b,c");
        }
    }

    @Test @DisplayName("both modes: missing nested field filled with empty string")
    void missingFieldBothModes() throws Exception {
        String input = "[{\"id\":1,\"address\":{\"city\":\"Athens\"}},{\"id\":2}]";
        for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
            String result = converter.jsonToCsv(input, mode);
            assertThat(result).contains("address.city").contains("Athens").contains("2");
        }
    }

    // ── Nested @DisplayName groups ────────────────────────────────────────────

    @Nested @DisplayName("1. Flat scalars only")
    class FlatScalars {
        private static final String JSON = "[{\"id\":1,\"name\":\"Alice\",\"active\":true}]";

        @Test @DisplayName("FLAT_FIRST: header + one data row, exact values")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(2, rows.size());
            assertEquals("id,name,active", rows.get(0));
            assertEquals("1,Alice,true",   rows.get(1));
        }

        @Test @DisplayName("CROSS_JOIN: identical output to FLAT_FIRST")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("2. Single primitive array")
    class PrimitiveArrayNested {
        private static final String JSON = "[{\"name\":\"cfg\",\"tags\":[\"a\",\"b\",\"c\"]}]";

        @Test @DisplayName("FLAT_FIRST: tags collapsed into one comma-separated cell")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).contains("tags"));
            assertTrue(rows.get(1).contains("a,b,c"));
        }

        @Test @DisplayName("CROSS_JOIN: same as FLAT_FIRST for pure primitive arrays")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("3. Single array-of-objects")
    class SingleObjectArray {
        private static final String JSON = """
                [{"title":"Report","items":[{"sku":"A","qty":1},{"sku":"B","qty":2}]}]
                """;

        @Test @DisplayName("FLAT_FIRST: expands 'items' — header + 2 data rows")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(3, rows.size(), "header + 2 rows from items");
            assertTrue(rows.get(1).startsWith("Report,"));
            assertTrue(rows.get(2).startsWith("Report,"));
            assertTrue(rows.get(1).contains("A"));
            assertTrue(rows.get(2).contains("B"));
        }

        @Test @DisplayName("CROSS_JOIN: same result as FLAT_FIRST with only one object-array")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("4. Two arrays-of-objects — FLAT_FIRST vs CROSS_JOIN diverge")
    class TwoObjectArrays {
        private static final String JSON = """
                [{"env":"prod",
                  "databases":[{"host":"db1","port":5432},{"host":"db2","port":5433}],
                  "users":[{"name":"Alice","role":"admin"},{"name":"Bob","role":"user"},{"name":"Carol","role":"user"}]
                }]
                """;

        @Test @DisplayName("FLAT_FIRST: expands only 'databases' → 2 data rows")
        void flatFirst_rowCount() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(3, rows.size());
        }

        @Test @DisplayName("FLAT_FIRST: 'users' column contains a JSON string, not expanded")
        void flatFirst_secondArraySerialised() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertTrue(rows.get(0).contains("users"));
            assertTrue(rows.get(1).contains("["));
        }

        @Test @DisplayName("CROSS_JOIN: produces 2 × 3 = 6 data rows")
        void crossJoin_rowCount() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
            assertEquals(7, rows.size());
        }

        @Test @DisplayName("CROSS_JOIN: every combination of db-host and user-name is present")
        void crossJoin_cartesianValues() throws Exception {
            List<String> rows     = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
            List<String> dataRows = rows.subList(1, rows.size());

            assertEquals(3, dataRows.stream().filter(r -> r.contains("db1")).count());
            assertEquals(3, dataRows.stream().filter(r -> r.contains("db2")).count());
            assertEquals(2, dataRows.stream().filter(r -> r.contains("Alice")).count());
            assertEquals(2, dataRows.stream().filter(r -> r.contains("Bob")).count());
            assertEquals(2, dataRows.stream().filter(r -> r.contains("Carol")).count());
        }
    }

    @Nested @DisplayName("5. Nested plain object — dot-flattened")
    class NestedObjectNested {
        private static final String JSON = """
                [{"id":42,"address":{"street":"Main St","city":"Athens","zip":"10001"}}]
                """;

        @Test @DisplayName("FLAT_FIRST: nested fields appear as address.street etc.")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertTrue(rows.get(0).contains("address.street"));
            assertTrue(rows.get(0).contains("address.city"));
            assertTrue(rows.get(0).contains("address.zip"));
            assertEquals(2, rows.size());
            assertTrue(rows.get(1).contains("Main St"));
            assertTrue(rows.get(1).contains("Athens"));
        }

        @Test @DisplayName("CROSS_JOIN: same dot-flattening as FLAT_FIRST")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("6. Nested object inside array element")
    class NestedObjectInsideArray {
        private static final String JSON = """
                [{"env":"prod","databases":[
                  {"host":"db1","pool":{"max":20,"min":5}},
                  {"host":"db2","pool":{"max":10,"min":2}}
                ]}]
                """;

        @Test @DisplayName("FLAT_FIRST: pool fields dot-flattened per database row")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(3, rows.size());
            assertTrue(rows.get(0).contains("databases.pool.max"));
            assertTrue(rows.get(0).contains("databases.pool.min"));
            assertTrue(rows.get(1).contains("db1") && rows.get(1).contains("20"));
            assertTrue(rows.get(2).contains("db2") && rows.get(2).contains("10"));
        }

        @Test @DisplayName("CROSS_JOIN: same as FLAT_FIRST — single object-array present")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("7. Null and missing values")
    class NullValues {
        private static final String JSON = """
                [{"a":"x","b":null,"c":"y"},{"a":"p","c":"q"}]
                """;

        @Test @DisplayName("FLAT_FIRST: null → empty cell, missing key → empty cell")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(3, rows.size());
            assertTrue(rows.get(1).matches(".*x,,y.*"), "null b should be empty: " + rows.get(1));
            assertTrue(rows.get(2).matches(".*p,,q.*"), "missing b should be empty: " + rows.get(2));
        }

        @Test @DisplayName("CROSS_JOIN: same null handling as FLAT_FIRST")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("8. Bare single object input")
    class BareObject {
        private static final String JSON = "{\"id\":7,\"label\":\"solo\"}";

        @Test @DisplayName("FLAT_FIRST: auto-wraps object and produces 1 data row")
        void flatFirst() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(2, rows.size());
            assertEquals("id,label", rows.get(0));
            assertEquals("7,solo",   rows.get(1));
        }

        @Test @DisplayName("CROSS_JOIN: same auto-wrap behaviour")
        void crossJoin() throws Exception {
            assertEquals(
                  jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST),
                  jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
        }
    }

    @Nested @DisplayName("9. Realistic end-to-end (feature-request sample)")
    class EndToEnd {
        private static final String JSON = """
                {"title":"My Application","version":"1.0.0","debug":true,"port":8080,"timeout":30.5,
                 "allowed_hosts":["localhost","example.com","api.example.com"],
                 "database":[
                   {"host":"localhost","port":5432,"username":"admin","password":"secret","ssl":true,
                    "pool":{"max_connections":20,"min_connections":5}},
                   {"host":"localhost2","port":5422,"username":"admin","password":"secret","ssl":true,
                    "pool":{"max_connections":20,"min_connections":5}}
                 ],
                 "users":[
                   {"name":"Alice","email":"alice@example.com","role":"admin"},
                   {"name":"Bob","email":"bob@example.com","role":"user"}
                 ]}
                """;

        @Test @DisplayName("FLAT_FIRST: 2 data rows (first array = database)")
        void flatFirst_rowCount() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(3, rows.size());
        }

        @Test @DisplayName("FLAT_FIRST: allowed_hosts collapsed, users serialised as JSON string")
        void flatFirst_cellContent() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertTrue(rows.get(1).contains("localhost,example.com,api.example.com"));
            assertTrue(rows.get(1).contains("["), "users must be a JSON string");
        }

        @Test @DisplayName("FLAT_FIRST: pool fields dot-flattened on every database row")
        void flatFirst_poolFlattened() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertTrue(rows.get(0).contains("database.pool.max_connections"));
            assertTrue(rows.get(1).contains("20"));
            assertTrue(rows.get(2).contains("20"));
        }

        @Test @DisplayName("CROSS_JOIN: 2 × 2 = 4 data rows")
        void crossJoin_rowCount() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
            assertEquals(5, rows.size());
        }

        @Test @DisplayName("CROSS_JOIN: all four db+user combinations present")
        void crossJoin_combinations() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
            List<String> data = rows.subList(1, rows.size());

            assertTrue(data.stream().anyMatch(r -> r.contains("localhost,") && r.contains("Alice")));
            assertTrue(data.stream().anyMatch(r -> r.contains("localhost,") && r.contains("Bob")));
            assertTrue(data.stream().anyMatch(r -> r.contains("localhost2") && r.contains("Alice")));
            assertTrue(data.stream().anyMatch(r -> r.contains("localhost2") && r.contains("Bob")));
        }

        @Test @DisplayName("CROSS_JOIN: users.name and users.role columns present")
        void crossJoin_usersExpanded() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
            assertTrue(rows.get(0).contains("users.name"));
            assertTrue(rows.get(0).contains("users.role"));
        }
    }

    @Nested @DisplayName("10. Empty array-of-objects")
    class EmptyObjectArray {
        private static final String JSON = "[{\"title\":\"empty\",\"items\":[]}]";

        @Test @DisplayName("FLAT_FIRST: does not throw; at least header produced")
        void flatFirst() {
            assertDoesNotThrow(() -> {
                List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
                assertTrue(rows.size() >= 1);
            });
        }

        @Test @DisplayName("CROSS_JOIN: does not throw; at least header produced")
        void crossJoin() {
            assertDoesNotThrow(() -> {
                List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
                assertTrue(rows.size() >= 1);
            });
        }
    }

    @Nested @DisplayName("11. Three arrays-of-objects")
    class ThreeObjectArrays {
        private static final String JSON = """
                [{"envs":[{"e":"prod"},{"e":"staging"}],
                  "regions":[{"r":"eu"},{"r":"us"}],
                  "tenants":[{"t":"A"},{"t":"B"},{"t":"C"}]}]
                """;

        @Test @DisplayName("CROSS_JOIN: 2 × 2 × 3 = 12 data rows")
        void crossJoin_tripleProduct() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.CROSS_JOIN));
            assertEquals(13, rows.size());
        }

        @Test @DisplayName("FLAT_FIRST: only first array ('envs') expanded → 2 data rows")
        void flatFirst_onlyFirstExpanded() throws Exception {
            List<String> rows = lines(jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
            assertEquals(3, rows.size());
        }
    }

    @Nested @DisplayName("12. Default overload == FLAT_FIRST")
    class DefaultOverload {
        private static final String JSON = "[{\"x\":1,\"tags\":[{\"v\":\"a\"},{\"v\":\"b\"}]}]";

        @Test @DisplayName("jsonToCsv(json) == jsonToCsv(json, FLAT_FIRST)")
        void defaultEqualsFlatFirst() throws Exception {
            assertEquals(
                  converter.jsonToCsv(JSON),
                  converter.jsonToCsv(JSON, CsvConverter.CsvMode.FLAT_FIRST));
        }
    }
}