package com.converter.converter;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

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
@DisplayName("CsvConverter – CsvMode New Scenarios")
class CsvConverterModeNewScenariosTest {

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