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

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CsvConverter.estimateRowCount — the preview estimate used by the
 * UI to warn about row explosion before running a CROSS_JOIN conversion.
 * The estimate must match the number of data rows jsonToCsv actually emits.
 */
@DisplayName("CsvConverter – Row Estimation")
class CsvConverterRowEstimateTest {

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
