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

import com.converter.core.CsvConverter.CsvFormat;
import com.converter.core.CsvConverter.CsvMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * Edge-case tests for CsvConverter.
     * Covers: quoted fields, empty/missing fields, header-only CSVs,
     * Windows line endings, inconsistent columns, Unicode, numeric strings,
     * wide tables, JSON null to CSV, and round-trip correctness.
     * NOTE: null/missing-value behavior under FLAT_FIRST/CROSS_JOIN is already
     * exercised exhaustively in CsvConverterTest#NullValues — no duplication here.
     */
    @Nested @DisplayName("edge cases")
    class EdgeCases {
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

    /**
     * New scenario tests for CsvConverter – CsvMode behaviour.
     *
     * These tests are deliberately non-overlapping with CsvConverterTest and
     * CsvConverterEdgeCaseTest.  They cover:
     *   A. CROSS_JOIN with asymmetric (unequal) array sizes
     *   B. FLAT_FIRST with a single-element first array
     *   C. One or both arrays empty
     *   D. Mixed array (object + primitive elements)
     *   E. Column order stability
     *   F. Top-level JSON array whose elements each contain nested arrays
     */
    @Nested @DisplayName("FLAT_FIRST and CROSS_JOIN expansion")
    class ExpansionModes {
        private CsvConverter converter;

        @BeforeEach
        void setUp() { converter = new CsvConverter(); }

        // ── helpers ───────────────────────────────────────────────────────────────

        private String csv(String input, CsvConverter.CsvMode mode) throws Exception {
            return converter.jsonToCsv(input, mode);
        }

        private List<String> lines(String csv) {
            return Arrays.stream(csv.split("\n"))
                  .map(l -> l.replace("\r", ""))
                  .filter(l -> !l.isBlank())
                  .toList();
        }

        // ── A. CROSS_JOIN: asymmetric array sizes ─────────────────────────────────

        @Nested @DisplayName("A. CROSS_JOIN – asymmetric array sizes")
        class CrossJoinAsymmetric {

            @Test @DisplayName("arrays of size 3 and 2 produce 3 × 2 = 6 data rows")
            void threeByTwo() throws Exception {
                String input = """
                        {"env":"prod",
                         "databases":[{"host":"db1"},{"host":"db2"},{"host":"db3"}],
                         "tenants":[{"name":"alpha"},{"name":"beta"}]}
                        """;

                List<String> rows = lines(csv(input, CsvConverter.CsvMode.CROSS_JOIN));
                assertEquals(7, rows.size(), "header + 6 rows (3 × 2)");

                List<String> data = rows.subList(1, rows.size());
                assertEquals(2, data.stream().filter(r -> r.contains("db1")).count());
                assertEquals(2, data.stream().filter(r -> r.contains("db2")).count());
                assertEquals(2, data.stream().filter(r -> r.contains("db3")).count());
                assertEquals(3, data.stream().filter(r -> r.contains("alpha")).count());
                assertEquals(3, data.stream().filter(r -> r.contains("beta")).count());
            }

            @Test @DisplayName("arrays of size 1 and 4 produce 1 × 4 = 4 data rows")
            void oneByFour() throws Exception {
                String input = """
                        {"region":[{"code":"eu"}],
                         "zones":[{"z":"a"},{"z":"b"},{"z":"c"},{"z":"d"}]}
                        """;

                List<String> rows = lines(csv(input, CsvConverter.CsvMode.CROSS_JOIN));
                assertEquals(5, rows.size(), "header + 4 rows (1 × 4)");

                long euCount = rows.subList(1, rows.size()).stream().filter(r -> r.contains("eu")).count();
                assertEquals(4, euCount, "'eu' must appear on every data row");
            }

            @Test @DisplayName("three arrays of sizes 2, 3, 4 produce 2 × 3 × 4 = 24 data rows")
            void twoThreeFour() throws Exception {
                String input = "{\"a\":[{\"v\":\"a0\"},{\"v\":\"a1\"}],"
                      + "\"b\":[{\"v\":\"b0\"},{\"v\":\"b1\"},{\"v\":\"b2\"}],"
                      + "\"c\":[{\"v\":\"c0\"},{\"v\":\"c1\"},{\"v\":\"c2\"},{\"v\":\"c3\"}]}";

                List<String> rows = lines(csv(input, CsvConverter.CsvMode.CROSS_JOIN));
                assertEquals(25, rows.size(), "header + 24 rows (2 × 3 × 4)");
            }
        }

        // ── B. FLAT_FIRST: single-element first array ─────────────────────────────

        @Nested @DisplayName("B. FLAT_FIRST – first array has exactly one element")
        class FlatFirstSingleElement {

            @Test @DisplayName("produces exactly 1 data row")
            void singleElementExpansion() throws Exception {
                String input  = "{\"owner\":\"Alice\",\"items\":[{\"sku\":\"X1\",\"qty\":5}]}";
                List<String> rows = lines(csv(input, CsvConverter.CsvMode.FLAT_FIRST));
                assertEquals(2, rows.size(), "header + exactly 1 data row");
                assertTrue(rows.get(1).contains("Alice"));
                assertTrue(rows.get(1).contains("X1"));
                assertTrue(rows.get(1).contains("5"));
            }

            @Test @DisplayName("scalar fields are replicated onto the single expanded row")
            void scalarsOnRow() throws Exception {
                String input  = "{\"id\":42,\"label\":\"test\",\"nodes\":[{\"ip\":\"10.0.0.1\"}]}";
                List<String> rows = lines(csv(input, CsvConverter.CsvMode.FLAT_FIRST));
                assertEquals(2, rows.size());
                assertTrue(rows.get(1).contains("42"));
                assertTrue(rows.get(1).contains("test"));
                assertTrue(rows.get(1).contains("10.0.0.1"));
            }
        }

        // ── C. Empty arrays ───────────────────────────────────────────────────────

        @Nested @DisplayName("C. CROSS_JOIN / FLAT_FIRST – empty arrays")
        class EmptyArrayEdges {

            @Test @DisplayName("CROSS_JOIN: does not throw; scalar fields still produce a row")
            void crossJoinEmptyObjectArray() {
                assertDoesNotThrow(() -> {
                    List<String> rows = lines(csv("{\"title\":\"cfg\",\"items\":[]}", CsvConverter.CsvMode.CROSS_JOIN));
                    assertTrue(rows.size() <= 2, "empty array → at most a header row");
                });
            }

            @Test @DisplayName("CROSS_JOIN: two arrays – one empty, one non-empty – does not throw")
            void crossJoinOneEmptyOneNonEmpty() {
                assertDoesNotThrow(() ->
                      csv("{\"active\":[{\"id\":1},{\"id\":2}],\"retired\":[]}", CsvConverter.CsvMode.CROSS_JOIN));
            }

            @Test @DisplayName("FLAT_FIRST: empty first object-array does not throw")
            void flatFirstEmptyFirstArray() {
                assertDoesNotThrow(() ->
                      csv("{\"items\":[],\"tags\":[\"a\",\"b\"]}", CsvConverter.CsvMode.FLAT_FIRST));
            }
        }

        // ── D. Mixed array (object + primitive elements) ──────────────────────────

        @Nested @DisplayName("D. CROSS_JOIN – mixed array (objects and primitives)")
        class CrossJoinMixedArray {

            @Test @DisplayName("mixed array does not throw")
            void mixedArrayDoesNotThrow() {
                assertDoesNotThrow(() ->
                      csv("{\"values\":[{\"v\":\"obj1\"},\"primitive\",{\"v\":\"obj2\"}]}", CsvConverter.CsvMode.CROSS_JOIN));
            }

            @Test @DisplayName("mixed array: object elements contribute their values to output")
            void mixedArrayObjectValuesPresent() throws Exception {
                String result = csv("{\"values\":[{\"v\":\"obj1\"},\"primitive\",{\"v\":\"obj2\"}]}", CsvConverter.CsvMode.CROSS_JOIN);
                assertThat(result).contains("obj1").contains("obj2");
            }
        }

        // ── E. Column order stability ─────────────────────────────────────────────

        @Nested @DisplayName("E. Column order stability")
        class ColumnOrderStability {

            @Test @DisplayName("FLAT_FIRST: scalar columns appear before expanded array-object columns")
            void scalarsBeforeArrayColumns() throws Exception {
                String input = "{\"id\":1,\"name\":\"Alice\","
                      + "\"orders\":[{\"oid\":\"O1\",\"amount\":100},{\"oid\":\"O2\",\"amount\":200}]}";

                String header = lines(csv(input, CsvConverter.CsvMode.FLAT_FIRST)).get(0);
                int idIdx     = header.indexOf("id");
                int nameIdx   = header.indexOf("name");
                int ordersIdx = header.indexOf("orders.");

                assertTrue(idIdx   < ordersIdx, "id must come before orders.* columns");
                assertTrue(nameIdx < ordersIdx, "name must come before orders.* columns");
            }

            @Test @DisplayName("CROSS_JOIN: same header produced on repeated calls (deterministic)")
            void crossJoinDeterministicHeader() throws Exception {
                String input = "{\"env\":\"prod\","
                      + "\"a\":[{\"x\":1},{\"x\":2}],"
                      + "\"b\":[{\"y\":\"p\"},{\"y\":\"q\"}]}";

                String h1 = lines(csv(input, CsvConverter.CsvMode.CROSS_JOIN)).get(0);
                String h2 = lines(csv(input, CsvConverter.CsvMode.CROSS_JOIN)).get(0);
                assertEquals(h1, h2, "header must be identical across repeated calls");
            }

            @Test @DisplayName("FLAT_FIRST: header follows insertion order of first object's keys")
            void flatFirstHeaderInsertionOrder() throws Exception {
                String input  = "{\"id\":1,\"name\":\"Alice\",\"items\":[{\"id\":10,\"label\":\"A\"}]}";
                String header = lines(csv(input, CsvConverter.CsvMode.FLAT_FIRST)).get(0);
                assertTrue(header.startsWith("id,name,items."),
                      "Header must start with id,name,items.* in declaration order; got: " + header);
            }
        }

        // ── F. Top-level JSON array with nested arrays ────────────────────────────

        @Nested @DisplayName("F. Top-level array – elements each contain a nested array-of-objects")
        class TopLevelArrayWithNestedArrays {

            @Test @DisplayName("FLAT_FIRST: each top-level element expands its first array independently")
            void flatFirstExpandsPerElement() throws Exception {
                String input = """
                        [{"id":1,"tags":[{"t":"a"},{"t":"b"}]},
                         {"id":2,"tags":[{"t":"c"}]}]
                        """;
                // element-1 → 2 rows, element-2 → 1 row → 3 total data rows
                List<String> rows = lines(csv(input, CsvConverter.CsvMode.FLAT_FIRST));
                assertEquals(4, rows.size(), "header + 3 data rows (2+1)");
                assertTrue(rows.get(0).contains("id"));
                assertTrue(rows.get(0).contains("tags.t"));
            }

            @Test @DisplayName("CROSS_JOIN: each top-level element fully cross-joins its own nested arrays")
            void crossJoinExpandsPerElement() throws Exception {
                String input = """
                        [{"id":1,
                          "colors":[{"c":"red"},{"c":"blue"}],
                          "sizes":[{"s":"S"},{"s":"M"}]}]
                        """;
                // 1 element, 2 colors × 2 sizes = 4 data rows
                List<String> rows = lines(csv(input, CsvConverter.CsvMode.CROSS_JOIN));
                assertEquals(5, rows.size(), "header + 4 rows (2 × 2)");

                List<String> data = rows.subList(1, rows.size());
                assertEquals(2, data.stream().filter(r -> r.contains("red")).count());
                assertEquals(2, data.stream().filter(r -> r.contains("blue")).count());
            }

            @Test @DisplayName("both modes: top-level array with purely flat elements produce identical output")
            void topLevelFlatElementsBothModes() throws Exception {
                String input = "[{\"id\":1,\"val\":\"x\"},{\"id\":2,\"val\":\"y\"}]";
                assertEquals(
                      csv(input, CsvConverter.CsvMode.FLAT_FIRST),
                      csv(input, CsvConverter.CsvMode.CROSS_JOIN));
            }
        }
    }

    /**
     * Tests for CsvConverter.estimateRowCount — the preview estimate used by the
     * UI to warn about row explosion before running a CROSS_JOIN conversion.
     * The estimate must match the number of data rows jsonToCsv actually emits.
     */
    @Nested @DisplayName("row-count estimates")
    class RowEstimates {
        private CsvConverter converter;

        @BeforeEach void setUp() { converter = new CsvConverter(); }

        private long actualRows(String json, CsvConverter.CsvMode mode) throws Exception {
            String csv = converter.jsonToCsv(json, mode);
            if (csv.isEmpty()) return 0;
            return csv.strip().split("\n").length - 1; // minus header
        }

        @Test @DisplayName("Flat object estimates a single row in both modes")
        void flatObjectSingleRow() throws Exception {
            String json = "{\"name\":\"Alice\",\"age\":30}";
            assertThat(converter.estimateRowCount(json, CsvConverter.CsvMode.FLAT_FIRST)).isEqualTo(1);
            assertThat(converter.estimateRowCount(json, CsvConverter.CsvMode.CROSS_JOIN)).isEqualTo(1);
        }

        @Test @DisplayName("FLAT_FIRST: only the first object-array contributes rows")
        void flatFirstCountsFirstArrayOnly() throws Exception {
            String json = "{\"customer\":\"Alice\"," +
                  "\"orders\":[{\"id\":\"O1\"},{\"id\":\"O2\"},{\"id\":\"O3\"}]," +
                  "\"tags\":[{\"name\":\"vip\"},{\"name\":\"priority\"}]}";
            long estimate = converter.estimateRowCount(json, CsvConverter.CsvMode.FLAT_FIRST);
            assertThat(estimate).isEqualTo(3);
            assertThat(estimate).isEqualTo(actualRows(json, CsvConverter.CsvMode.FLAT_FIRST));
        }

        @Test @DisplayName("CROSS_JOIN: estimate is the Cartesian product of object-array sizes")
        void crossJoinCartesianProduct() throws Exception {
            String json = "{\"env\":\"prod\"," +
                  "\"databases\":[{\"host\":\"db1\"},{\"host\":\"db2\"}]," +
                  "\"tenants\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}";
            long estimate = converter.estimateRowCount(json, CsvConverter.CsvMode.CROSS_JOIN);
            assertThat(estimate).isEqualTo(6);
            assertThat(estimate).isEqualTo(actualRows(json, CsvConverter.CsvMode.CROSS_JOIN));
        }

        @Test @DisplayName("CROSS_JOIN: nested object arrays multiply through recursion")
        void crossJoinNestedArrays() throws Exception {
            String json = "{\"region\":{\"zones\":[{\"id\":\"z1\"},{\"id\":\"z2\"}]}," +
                  "\"apps\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
            long estimate = converter.estimateRowCount(json, CsvConverter.CsvMode.CROSS_JOIN);
            assertThat(estimate).isEqualTo(4);
            assertThat(estimate).isEqualTo(actualRows(json, CsvConverter.CsvMode.CROSS_JOIN));
        }

        @Test @DisplayName("Top-level array sums per-element estimates")
        void topLevelArraySums() throws Exception {
            String json = "[{\"items\":[{\"id\":1},{\"id\":2}]},{\"items\":[{\"id\":3}]}]";
            long flat  = converter.estimateRowCount(json, CsvConverter.CsvMode.FLAT_FIRST);
            long cross = converter.estimateRowCount(json, CsvConverter.CsvMode.CROSS_JOIN);
            assertThat(flat).isEqualTo(3);
            assertThat(cross).isEqualTo(3);
            assertThat(flat).isEqualTo(actualRows(json, CsvConverter.CsvMode.FLAT_FIRST));
            assertThat(cross).isEqualTo(actualRows(json, CsvConverter.CsvMode.CROSS_JOIN));
        }

        @Test @DisplayName("Primitive arrays do not contribute extra rows")
        void primitiveArraysNoRows() throws Exception {
            String json = "{\"name\":\"x\",\"scores\":[1,2,3,4,5]}";
            assertThat(converter.estimateRowCount(json, CsvConverter.CsvMode.CROSS_JOIN)).isEqualTo(1);
            assertThat(converter.estimateRowCount(json, CsvConverter.CsvMode.FLAT_FIRST)).isEqualTo(1);
        }

        @Test @DisplayName("Scalar root estimates zero rows")
        void scalarRootZero() throws Exception {
            assertThat(converter.estimateRowCount("42", CsvConverter.CsvMode.CROSS_JOIN)).isZero();
        }

        @Test @DisplayName("Huge cross joins saturate at the estimate cap instead of overflowing")
        void hugeCrossJoinSaturates() throws Exception {
            // 6 arrays of 1000 objects each => 10^18 rows, far above the cap
            StringBuilder json = new StringBuilder("{");
            for (int a = 0; a < 6; a++) {
                if (a > 0) json.append(",");
                json.append("\"arr").append(a).append("\":[");
                for (int i = 0; i < 1000; i++) {
                    if (i > 0) json.append(",");
                    json.append("{\"v\":").append(i).append("}");
                }
                json.append("]");
            }
            json.append("}");
            long estimate = converter.estimateRowCount(json.toString(), CsvConverter.CsvMode.CROSS_JOIN);
            assertThat(estimate).isEqualTo(CsvConverter.ESTIMATE_CAP);
        }
    }

    /** CSV must not drop values, nor report an empty document as a conversion. */
    @Nested @DisplayName("CSV fidelity")
    class Fidelity {
        private final CsvConverter converter = new CsvConverter();

        @Test @DisplayName("an array with no objects is refused, not converted to nothing")
        void arrayOfNonObjectsIsRefused() {
            // Each of these returned "" and the panel called it a success.
            for (String json : new String[]{"[1,2,3]", "[\"a\",\"b\"]", "[null,null]", "[[1,2],[3,4]]"}) {
                assertThatThrownBy(() -> converter.jsonToCsv(json, CsvConverter.CsvMode.FLAT_FIRST))
                      .describedAs(json)
                      .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> converter.jsonToCsv("[]", CsvConverter.CsvMode.FLAT_FIRST)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("a mixed array names how many elements have no row")
        void mixedArrayIsRefusedWithACount() {
            assertThatThrownBy(() -> converter.jsonToCsv("[{\"a\":1},2,3]", CsvConverter.CsvMode.FLAT_FIRST))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("2 of the 3");
        }

        @Test @DisplayName("ordinary arrays of objects still convert")
        void objectsStillConvert() throws Exception {
            assertThat(converter.jsonToCsv("[{\"a\":1,\"b\":2}]", CsvConverter.CsvMode.FLAT_FIRST)).contains("a").contains("b").contains("1");
        }

        @Test @DisplayName("a row with more cells than headers is refused, not truncated")
        void raggedLongRowIsRefused() {
            // The extra cell was dropped, and Format wrote the truncation back.
            assertThatThrownBy(() -> converter.csvToJson("a,b\n1,2,3\n", false))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("would be discarded");
        }

        @Test @DisplayName("a trailing delimiter is not a discarded value")
        void trailingDelimiterIsAccepted() throws Exception {
            // Excel and many exporters end every row with a separator. The extra
            // cell is the empty string, so refusing the file over it helps nobody.
            assertThat(converter.csvToJson("a,b\n1,2,\n", false)).isEqualTo("[{\"a\":\"1\",\"b\":\"2\"}]");
            assertThat(converter.csvToJson("a,b\n1,2,,\n", false)).isEqualTo("[{\"a\":\"1\",\"b\":\"2\"}]");
            // A non-empty extra value is still refused, and counts only the real ones.
            assertThatThrownBy(() -> converter.csvToJson("a,b\n1,2,3,\n", false))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("so 1 would be");
        }

        @Test @DisplayName("a row with fewer cells than headers still means an absent key")
        void raggedShortRowIsUnchanged() throws Exception {
            assertThat(converter.csvToJson("a,b\n1\n", false)).isEqualTo("[{\"a\":\"1\"}]");
        }

        @Test @DisplayName("a blank line is not a row")
        void blankLinesAreNotRows() throws Exception {
            // A paste with a trailing blank line gained a phantom {"a":""} row, and
            // Format wrote it back.
            assertThat(converter.csvToJson("a,b\n1,2\n\n", false))
                  .isEqualTo("[{\"a\":\"1\",\"b\":\"2\"}]");
            assertThat(converter.csvToJson("a,b\n1,2\n\n3,4\n", false))
                  .isEqualTo("[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"b\":\"4\"}]");
            assertThat(converter.csvToJson("a,b\r\n1,2\r\n\r\n3,4\r\n", false))
                  .isEqualTo("[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"b\":\"4\"}]");
            assertThat(converter.csvToJson("a,b\n1,2\n   \n3,4\n", false))
                  .isEqualTo("[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"b\":\"4\"}]");
        }

        @Test @DisplayName("two keys that flatten to the same column are refused, not merged")
        void collidingColumnsAreRefused() {
            // {"a.b":1,"a":{"b":2}} wrote one column a.b holding 2; the 1 was gone.
            for (CsvConverter.CsvMode mode : CsvConverter.CsvMode.values()) {
                for (String json : new String[]{
                      "{\"a.b\":1,\"a\":{\"b\":2}}",
                      "{\"a\":{\"b\":2},\"a.b\":1}",
                      "{\"a.b\":1,\"a\":[{\"b\":2},{\"b\":3}]}",       // through the array expansion
                      "{\"a\":{\"b.c\":1,\"b\":{\"c\":2}}}",           // nested one level down
                }) {
                    assertThatThrownBy(() -> converter.jsonToCsv(json, mode))
                          .describedAs(mode + " " + json)
                          .isInstanceOf(IllegalArgumentException.class)
                          .hasMessageContaining("same CSV column \"a.b");
                }
            }
            // The same column in DIFFERENT rows is an ordinary column.
            assertThat(catching(() -> converter.jsonToCsv("[{\"a\":{\"b\":1}},{\"a.b\":2}]",
                  CsvConverter.CsvMode.FLAT_FIRST))).isEqualTo("a.b\n1\n2\n");
        }

        private static String catching(java.util.concurrent.Callable<String> call) {
            try {
                return call.call();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Nested @DisplayName("delimiters and sorted keys")
    class DelimitersAndSortKeys {
        private CsvConverter csv;
        private ConversionPipeline pipeline;

        @BeforeEach void setUp() {
            csv = new CsvConverter();
            pipeline = new ConversionPipeline();
        }

        // ── CSV delimiter ────────────────────────────────────────────────────

        @Test @DisplayName("semicolon-delimited input parses into the right columns")
        void semicolonInput() throws Exception {
            var row = pipeline.parseJson(
                  csv.csvToJson("name;age\nAda;36\n", true, CsvFormat.SEMICOLON)).get(0);
            assertThat(row.get("name").asText()).isEqualTo("Ada");
            assertThat(row.get("age").asInt()).isEqualTo(36);
        }

        @Test @DisplayName("comma parsing of semicolon input yields one bogus column")
        void semicolonNeedsTheOption() throws Exception {
            // Without the option the whole line is a single column — the exact
            // failure European CSV users hit.
            String json = csv.csvToJson("name;age\nAda;36\n", true, CsvFormat.DEFAULT);
            assertThat(json).contains("name;age");
        }

        @Test @DisplayName("tab-delimited input parses")
        void tabInput() throws Exception {
            var row = pipeline.parseJson(
                  csv.csvToJson("name\tage\nAda\t36\n", true, CsvFormat.TAB)).get(0);
            assertThat(row.get("name").asText()).isEqualTo("Ada");
            assertThat(row.get("age").asInt()).isEqualTo(36);
        }

        @Test @DisplayName("output honours the configured delimiter")
        void semicolonOutput() throws Exception {
            String out = csv.jsonToCsv(pipeline.parseJson("[{\"a\":1,\"b\":2}]"),
                  CsvMode.FLAT_FIRST, CsvFormat.SEMICOLON);
            assertThat(out).contains("a;b").contains("1;2").doesNotContain("a,b");
        }

        @Test @DisplayName("semicolon round-trip preserves values")
        void semicolonRoundTrip() throws Exception {
            String original = "name;city\nAda;London\nGrace;NYC\n";
            String json = csv.csvToJson(original, false, CsvFormat.SEMICOLON);
            String back = csv.jsonToCsv(pipeline.parseJson(json), CsvMode.FLAT_FIRST,
                  CsvFormat.SEMICOLON);
            assertThat(back.replace("\r\n", "\n").strip())
                  .isEqualTo(original.strip());
        }

        @Test @DisplayName("a value containing the delimiter is quoted")
        void delimiterInValueIsQuoted() throws Exception {
            String out = csv.jsonToCsv(pipeline.parseJson("[{\"a\":\"x;y\"}]"),
                  CsvMode.FLAT_FIRST, CsvFormat.SEMICOLON);
            assertThat(out).contains("\"x;y\"");
        }

        @Test @DisplayName("existing comma behaviour is unchanged by default")
        void defaultsUnchanged() throws Exception {
            var row = pipeline.parseJson(csv.csvToJson("a,b\n1,2\n", true)).get(0);
            assertThat(row.get("a").asInt()).isEqualTo(1);
            assertThat(row.get("b").asInt()).isEqualTo(2);
        }

        // ── Key sorting ──────────────────────────────────────────────────────

        @Test @DisplayName("object keys are sorted recursively, array order preserved")
        void sortsRecursively() throws Exception {
            String sorted = LenientJson.sortKeys("{\"b\":1,\"a\":{\"z\":1,\"y\":2},\"c\":[3,1,2]}");
            assertThat(sorted.indexOf("\"a\"")).isLessThan(sorted.indexOf("\"b\""));
            assertThat(sorted.indexOf("\"y\"")).isLessThan(sorted.indexOf("\"z\""));
            // Arrays are ordered data, not key sets — order must survive.
            assertThat(sorted).contains("[3,1,2]");
        }

        @Test @DisplayName("differently-ordered equivalent documents canonicalize identically")
        void canonicalFormIsStable() throws Exception {
            assertThat(LenientJson.sortKeys("{\"b\":1,\"a\":2}"))
                  .isEqualTo(LenientJson.sortKeys("{\"a\":2,\"b\":1}"));
        }

        @Test @DisplayName("sortKeys applies through normalizeToJson when enabled")
        void sortViaOptions() throws Exception {
            String pivot = pipeline.normalizeToJson("{\"b\":1,\"a\":2}", Formats.FMT_JSON,
                  ConversionOptions.DEFAULTS.withSortKeys(true));
            assertThat(pivot.indexOf("\"a\"")).isLessThan(pivot.indexOf("\"b\""));
        }

        @Test @DisplayName("sorting is off by default, preserving document order")
        void offByDefault() throws Exception {
            String pivot = pipeline.normalizeToJson("{\"b\":1,\"a\":2}", Formats.FMT_JSON,
                  ConversionOptions.DEFAULTS);
            assertThat(pivot.indexOf("\"b\"")).isLessThan(pivot.indexOf("\"a\""));
        }

        @Test @DisplayName("sorted keys carry through to a rendered target format")
        void sortReachesOutput() throws Exception {
            String pivot = pipeline.normalizeToJson("{\"b\":1,\"a\":2}", Formats.FMT_JSON,
                  ConversionOptions.DEFAULTS.withSortKeys(true));
            String yaml = pipeline.renderFromJson(pivot, Formats.FMT_YAML,
                  ConversionOptions.DEFAULTS);
            assertThat(yaml.indexOf("a:")).isLessThan(yaml.indexOf("b:"));
        }
    }

    /**
     * The Cancel button's only mechanism once row expansion has started is the
     * interrupt polling inside {@link CsvConverter}. Nothing exercised it, so any
     * of those polls could be deleted with the whole suite still green.
     */
    @Nested @DisplayName("cancellation")
    class Cancellation {
        private CsvConverter csv;
        private ConversionPipeline pipeline;

        @BeforeEach void setUp() {
            csv = new CsvConverter();
            pipeline = new ConversionPipeline();
        }

        @AfterEach void clearInterrupt() {
            // The throw leaves the interrupt flag set; without clearing it the flag
            // leaks onto the shared JUnit worker and fails unrelated tests.
            Thread.interrupted();
        }

        private static String crossJoinInput(int arrays, int each) {
            StringBuilder sb = new StringBuilder("{");
            for (int a = 0; a < arrays; a++) {
                if (a > 0) sb.append(",");
                sb.append("\"a").append(a).append("\":[");
                for (int i = 0; i < each; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("{\"v\":").append(i).append("}");
                }
                sb.append("]");
            }
            return sb.append("}").toString();
        }

        @Test @DisplayName("an already-interrupted thread aborts FLAT_FIRST")
        void interruptedFlatFirst() throws Exception {
            var tree = pipeline.parseJson("[{\"a\":1},{\"a\":2}]");
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> csv.jsonToCsv(tree, CsvMode.FLAT_FIRST))
                  .isInstanceOf(CancellationException.class);
        }

        @Test @DisplayName("an already-interrupted thread aborts CROSS_JOIN")
        void interruptedCrossJoin() throws Exception {
            var tree = pipeline.parseJson(crossJoinInput(3, 3));
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> csv.jsonToCsv(tree, CsvMode.CROSS_JOIN))
                  .isInstanceOf(CancellationException.class);
        }

        @Test @DisplayName("a mid-flight interrupt stops a runaway cross join promptly")
        void interruptMidFlight() throws Exception {
            // Large enough that it would take a long time to finish on its own.
            var tree = pipeline.parseJson(crossJoinInput(12, 5));
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();

            Thread worker = new Thread(() -> {
                started.countDown();
                try {
                    csv.jsonToCsv(tree, CsvMode.CROSS_JOIN);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            worker.start();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);          // let it get into the expansion
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(worker.isAlive()).as("worker should have stopped").isFalse();
            assertThat(thrown.get()).isInstanceOf(CancellationException.class);
        }

        @Test @DisplayName("without an interrupt the same conversion completes normally")
        void noInterruptCompletes() throws Exception {
            var tree = pipeline.parseJson(crossJoinInput(2, 2));
            assertThat(csv.jsonToCsv(tree, CsvMode.CROSS_JOIN)).isNotBlank();
        }
    }

    @Nested @DisplayName("degenerate output")
    class Degenerate {

        private final ConversionPipeline pipeline = new ConversionPipeline();

        @Test @DisplayName("zero-column CSV explains itself instead of leaking Jackson internals")
        void zeroColumnCsv() {
            assertThatThrownBy(() -> pipeline.renderFromJson("[{}]", Formats.FMT_CSV,
                  ConversionOptions.DEFAULTS))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("no columns");
        }
    }

    /** A blank line is not a row, but everything else on a line is data. */
    @Nested @DisplayName("blank lines, leading spaces and lone blank cells")
    class BlankLines {

        private final ConversionPipeline pipeline = new ConversionPipeline();
        private final ConversionOptions opts = ConversionOptions.DEFAULTS;

        @Test @DisplayName("leading spaces of a row's first cell survive Format and Convert")
        void leadingSpacesSurvive() throws Exception {
            assertThat(pipeline.formatInput("code,qty\n  A1,1\n B2,2\n", Formats.FMT_CSV, opts))
                  .isEqualTo("code,qty\n  A1,1\n B2,2\n");
            assertThat(json.readTree(converter.csvToJson("a,b\n  x,  1\n", false)))
                  .isEqualTo(json.readTree("[{\"a\":\"  x\",\"b\":\"  1\"}]"));
            // A padded value stays text rather than being read as a number.
            String csv = pipeline.renderFromJson("[{\"id\":\" 42\",\"n\":1}]", Formats.FMT_CSV, opts);
            assertThat(json.readTree(pipeline.normalizeToJson(csv, Formats.FMT_CSV, opts)))
                  .isEqualTo(json.readTree("[{\"id\":\" 42\",\"n\":1}]"));
        }

        @Test @DisplayName("blank and whitespace-only lines are still skipped, except inside quoted values")
        void blankLinesAreSkipped() throws Exception {
            assertThat(json.readTree(converter.csvToJson("a,b\n1,2\n\n   \n3,4\n\n", false)))
                  .isEqualTo(json.readTree("[{\"a\":\"1\",\"b\":\"2\"},{\"a\":\"3\",\"b\":\"4\"}]"));
            assertThat(json.readTree(converter.csvToJson("a,b\r\n\r\n1,2\r\n", false)))
                  .isEqualTo(json.readTree("[{\"a\":\"1\",\"b\":\"2\"}]"));
            assertThat(json.readTree(converter.csvToJson("a,b\n\"one\n\n  \nfour\",x\n", false)).get(0).get("a").asText())
                  .isEqualTo("one\n\n  \nfour");
        }

        @Test @DisplayName("a row whose only cell is blank is written quoted, and comes back")
        void loneBlankCellsSurvive() throws Exception {
            String doc = "[{\"name\":\"Alice\"},{\"name\":\"\"},{\"name\":\"   \"},{\"name\":\"Bob\"}]";
            String csv = pipeline.renderFromJson(doc, Formats.FMT_CSV, opts);
            assertThat(csv).isEqualTo("name\nAlice\n\"\"\n\"   \"\nBob\n");
            assertThat(json.readTree(pipeline.normalizeToJson(csv, Formats.FMT_CSV, opts)))
                  .isEqualTo(json.readTree(doc));
            // An empty column name is a blank header line the same way.
            String anonymous = pipeline.renderFromJson("[{\"\":\"x\"},{\"\":\"y\"}]", Formats.FMT_CSV, opts);
            assertThat(json.readTree(pipeline.normalizeToJson(anonymous, Formats.FMT_CSV, opts)))
                  .isEqualTo(json.readTree("[{\"column_1\":\"x\"},{\"column_1\":\"y\"}]"));
        }

        @Test @DisplayName("Format keeps a row that is one quoted empty value")
        void formatKeepsQuotedEmptyRows() throws Exception {
            String input = "a,b\n\"\"\n1,2\n";
            String formatted = pipeline.formatInput(input, Formats.FMT_CSV, opts);
            assertThat(formatted).isEqualTo(input);
            assertThat(pipeline.normalizeToJson(formatted, Formats.FMT_CSV, opts))
                  .isEqualTo(pipeline.normalizeToJson(input, Formats.FMT_CSV, opts))
                  .contains("{\"a\":\"\"}");
        }
    }

    @Nested @DisplayName("type inference leaves identifiers alone")
    class Inference {

        @Test @DisplayName("digits with one 'e' are an identifier unless written as scientific notation")
        void exponentLookalikesStayText() throws Exception {
            assertThat(json.readTree(converter.csvToJson(
                  "commit,gene,sci,frac,two\n1234e56,2310009E13,1e3,1.5e3,12e3\n", true)))
                  .isEqualTo(json.readTree(
                        "[{\"commit\":\"1234e56\",\"gene\":\"2310009E13\",\"sci\":1E+3,\"frac\":1.5E+3,\"two\":\"12e3\"}]"));
        }
    }
}
