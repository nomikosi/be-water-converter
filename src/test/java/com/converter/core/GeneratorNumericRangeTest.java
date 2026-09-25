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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class GeneratorNumericRangeTest {
    @ParameterizedTest
    @ValueSource(strings = {"1e400", "1e-400", "0.12345678901234567890123456789", "-1e400"})
    void decimalTypesCanHoldTheSample(String value) throws Exception {
        String input = "{\"amount\":" + value + "}";
        assertThat(new JavaPojoGenerator().fromJson(input)).contains("private BigDecimal amount;");
        assertThat(new KotlinDataClassGenerator().fromJson(input)).contains("val amount: BigDecimal");
        assertThatThrownBy(() -> new ProtoConverter().jsonToProto(input))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as double");
    }

    @ParameterizedTest @ValueSource(strings = {"0.1", "1.5", "-2.25", "1e100", "1e-100"})
    void ordinaryDecimalsKeepDoubleTypes(String value) throws Exception {
        String input = "{\"amount\":" + value + "}";
        assertThat(new JavaPojoGenerator().fromJson(input)).contains("private Double amount;");
        assertThat(new KotlinDataClassGenerator().fromJson(input)).contains("val amount: Double");
        assertThat(new ProtoConverter().jsonToProto(input)).contains("double amount = 1;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "18446744073709551616"})
    void integersBeyondInt64AreNotAssignedInt64(String value) throws Exception {
        String input = "{\"amount\":" + value + "}";
        assertThat(new JavaPojoGenerator().fromJson(input)).contains("private BigInteger amount;");
        assertThat(new KotlinDataClassGenerator().fromJson(input)).contains("val amount: BigInteger");
        assertThatThrownBy(() -> new ProtoConverter().jsonToProto(input))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as int64");
    }

    @ParameterizedTest @ValueSource(strings = {"9223372036854775807", "-9223372036854775808"})
    void int64BoundaryValuesRemainSupported(String value) throws Exception {
        assertThat(new ProtoConverter().jsonToProto("{\"amount\":" + value + "}"))
              .contains("int64 amount = 1;");
    }

    @ParameterizedTest @ValueSource(strings = {
          "1.5,1e400", "1e-400,1.5", "9007199254740993,1.5", "1.5,9223372036854775808",
          "9007199254740992,9007199254740993,1.5", "9007199254740993,9007199254740992,1.5",
          "9007199254740993,1000000000000000000000000,1.5",
          "1000000000000000000000000,9007199254740993,1.5"})
    void arraysWidenWithoutLosingTheRangeOfOtherElements(String values) throws Exception {
        String input = "{\"amounts\":[" + values + "]}";
        assertThat(new JavaPojoGenerator().fromJson(input)).contains("List<BigDecimal> amounts;");
        assertThat(new KotlinDataClassGenerator().fromJson(input)).contains("val amounts: List<BigDecimal>");
        assertThatThrownBy(() -> new ProtoConverter().jsonToProto(input))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as double");
    }

    @Test void nestedAndRootArrayShapesUseTheSameRangeChecks() throws Exception {
        String input = "[{\"nested\":{\"amount\":1.5}},{\"nested\":{\"amount\":1e400}}]";
        assertThat(new JavaPojoGenerator().fromJson(input)).contains("private BigDecimal amount;");
        assertThat(new KotlinDataClassGenerator().fromJson(input)).contains("val amount: BigDecimal");
        assertThatThrownBy(() -> new ConversionPipeline().renderFromJson(input, "Protobuf", ConversionOptions.DEFAULTS))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as double");
    }
    @Test void wideningIntegralArraysStillUsesBigInteger() throws Exception {
        String input = "{\"amounts\":[9007199254740993,1000000000000000000000000]}";
        assertThat(new JavaPojoGenerator().fromJson(input)).contains("List<BigInteger> amounts;");
        assertThat(new KotlinDataClassGenerator().fromJson(input)).contains("val amounts: List<BigInteger>");
        assertThatThrownBy(() -> new ProtoConverter().jsonToProto(input))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as int64");
    }
}
