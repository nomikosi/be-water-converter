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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** XML has no nested-list form, so inner lists get an element of their own. */
@DisplayName("XML nested arrays")
class XmlNestedArrayTest {

    private final JsonXmlConverter converter = new JsonXmlConverter();

    @Test @DisplayName("an inner list becomes an element instead of merging into its neighbours")
    void innerListsKeepTheirGrouping() throws Exception {
        // Was <m>1</m><m>2</m><m>3</m><m>4</m>, i.e. {"m":[1,2,3,4]} on the way
        // back: the rows merged and a dimension vanished with no warning.
        String xml = converter.jsonToXml("{\"m\":[[1,2],[3,4]]}");
        assertThat(xml.replaceAll("\\s+", ""))
              .isEqualTo("<root><m><values>1</values><values>2</values></m>"
                    + "<m><values>3</values><values>4</values></m></root>");
        assertThat(converter.xmlToJson(xml, true))
              .isEqualTo("{\"m\":[{\"values\":[1,2]},{\"values\":[3,4]}]}");
    }

    @Test @DisplayName("an empty inner list keeps its element rather than vanishing")
    void emptyInnerListSurvives() throws Exception {
        assertThat(converter.jsonToXml("{\"m\":[[1,2],[]]}").replaceAll("\\s+", ""))
              .contains("<m><values>1</values><values>2</values></m>")
              .contains("<m/>");
    }

    @Test @DisplayName("depth beyond two nests the wrapper as well")
    void deeperNestingWraps() throws Exception {
        // Two wrapper levels, not three: a one-element array collapses to a bare
        // element in XML, which is pre-existing and inherent to the format. The
        // values and their grouping survive, which is what the fix is for.
        assertThat(converter.jsonToXml("{\"c\":[[[1.0,2.0]]]}").replaceAll("\\s+", ""))
              .isEqualTo("<root><c><values><values>1.0</values>"
                    + "<values>2.0</values></values></c></root>");
        // A two-element outer array does keep every level.
        assertThat(converter.jsonToXml("{\"c\":[[[1]],[[2]]]}").replaceAll("\\s+", ""))
              .isEqualTo("<root><c><values><values>1</values></values></c>"
                    + "<c><values><values>2</values></values></c></root>");
    }

    @Test @DisplayName("flat arrays and objects are untouched")
    void flatShapesUnchanged() throws Exception {
        assertThat(converter.jsonToXml("{\"m\":[1,2],\"n\":{\"a\":1}}").replaceAll("\\s+", ""))
              .isEqualTo("<root><m>1</m><m>2</m><n><a>1</a></n></root>");
        assertThat(converter.xmlToJson(converter.jsonToXml("{\"m\":[1,2]}"), true))
              .isEqualTo("{\"m\":[1,2]}");
    }
}
