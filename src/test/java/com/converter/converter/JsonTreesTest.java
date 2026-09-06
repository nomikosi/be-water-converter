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

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JsonTreesTest {
    @Test void sortsNestedObjectsWithoutChangingSourceArraysOrDecimalScale() throws Exception {
        String original = "{\"z\":[{\"b\":1.10,\"a\":2},3],\"a\":true}";
        var tree = PivotJson.mapper().readTree(original);
        assertThat(JsonTrees.sorted(tree).toString()).isEqualTo("{\"a\":true,\"z\":[{\"a\":2,\"b\":1.10},3]}");
        assertThat(tree.toString()).isEqualTo(original);
        assertThat(JsonTrees.sorted(tree, true).path("z").get(0).path("b").toString()).isEqualTo("1.1");
    }
}
