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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class KotlinJvmLimitsTest {
    static String input(int count, String value, boolean nullable) throws Exception {
        var mapper = PivotJson.mapper();
        var object = mapper.createObjectNode();
        var nulls = mapper.createObjectNode();
        for (int i = 0; i < count; i++) { object.set("f" + i, mapper.readTree(value)); nulls.putNull("f" + i); }
        return nullable ? mapper.createArrayNode().add(object).add(nulls).toString() : object.toString();
    }

    @ParameterizedTest @CsvSource({"124,1.5,false", "124,2147483648,false", "245,1,false", "245,1.5,true"})
    void supportedBoundariesGenerate(int count, String value, boolean nullable) throws Exception {
        assertThat(new KotlinDataClassGenerator().fromJson(input(count, value, nullable))).contains("data class Root(");
    }

    @ParameterizedTest @CsvSource({"125,1.5,false", "128,1.5,false", "125,2147483648,false", "246,1,false", "246,1.5,true", "255,1,false"})
    void oversizeGeneratedMethodsAreRejected(int count, String value, boolean nullable) {
        assertThatThrownBy(() -> new KotlinDataClassGenerator().fromJson(input(count, value, nullable)))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JVM parameter slots", "limit 255", "Root");
    }

    @Test void oversizedNestedClassesAreRejectedToo() throws Exception {
        String input = "{\"nested\":" + input(125, "1.5", false) + "}";
        assertThatThrownBy(() -> new KotlinDataClassGenerator().fromJson(input))
              .hasMessageContaining("Kotlin class Nested");
    }
}
