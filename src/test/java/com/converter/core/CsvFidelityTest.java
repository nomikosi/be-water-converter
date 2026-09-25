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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CSV must not drop values, nor report an empty document as a conversion. */
@DisplayName("CSV fidelity")
class CsvFidelityTest {

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
