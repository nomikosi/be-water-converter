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

/**
 * Arrays are typed from every element, not the first. Typing from element 0
 * turned {@code [1,"two"]} into {@code List<Integer>} and silently dropped
 * every key that only a later object carried.
 */
@DisplayName("Generators type arrays from every element")
class GeneratorShapeMergeTest {

    private final JavaPojoGenerator java = new JavaPojoGenerator();
    private final KotlinDataClassGenerator kotlin = new KotlinDataClassGenerator();
    private final ProtoConverter proto = new ProtoConverter();

    @Test @DisplayName("objects in an array contribute all their keys to one class")
    void objectKeysAreMerged() throws Exception {
        String json = "{\"items\":[{\"a\":1},{\"b\":\"x\"},{\"a\":2,\"c\":true}]}";
        assertThat(java.fromJson(json))
              .contains("private Integer a;").contains("private String b;").contains("private Boolean c;");
        // Every key was absent from some element, so each is optional in Kotlin.
        assertThat(kotlin.fromJson(json))
              .contains("val a: Int?").contains("val b: String?").contains("val c: Boolean?");
    }

    @Test @DisplayName("keys present in every element stay non-null in Kotlin")
    void keysSeenEverywhereStayRequired() throws Exception {
        String kt = kotlin.fromJson("{\"items\":[{\"id\":1,\"tag\":\"a\"},{\"id\":2,\"tag\":\"b\"}]}");
        assertThat(kt).contains("val id: Int,").contains("val tag: String");
        assertThat(kt).doesNotContain("?");
    }

    @Test @DisplayName("a key that is null in one element is nullable, typed from the others")
    void nullInOneElementMakesTheKeyNullable() throws Exception {
        String json = "{\"items\":[{\"n\":null},{\"n\":5}]}";
        assertThat(kotlin.fromJson(json)).contains("val n: Int?");
        assertThat(java.fromJson(json)).contains("private Integer n;");
    }

    @Test @DisplayName("mixed scalar kinds fall back to the top type; numbers widen")
    void scalarsWidenOrFallBack() throws Exception {
        assertThat(java.fromJson("{\"xs\":[1,\"two\"]}")).contains("List<Object> xs");
        assertThat(kotlin.fromJson("{\"xs\":[1,\"two\"]}")).contains("List<Any>");
        assertThat(java.fromJson("{\"ns\":[1,2.5]}")).contains("List<Double> ns");
        assertThat(java.fromJson("{\"ns\":[1,12345678901]}")).contains("List<Long> ns");
        assertThat(kotlin.fromJson("{\"ns\":[1,null,3]}")).contains("List<Int?>");
        assertThat(kotlin.fromJson("{\"ns\":[null,null]}")).contains("List<Any?>");
    }

    @Test @DisplayName("a root array is merged the same way")
    void rootArrayIsMerged() throws Exception {
        assertThat(java.fromJson("[{\"a\":1},{\"b\":2}]"))
              .contains("private Integer a;").contains("private Integer b;");
        assertThat(java.fromJson("[[{\"a\":1}],[{\"b\":2}]]"))
              .contains("private Integer a;").contains("private Integer b;");
    }

    @Test @DisplayName("nested arrays of objects merge at every level")
    void nestedArraysMerge() throws Exception {
        assertThat(java.fromJson("{\"grid\":[[{\"a\":1}],[{\"b\":2}]]}"))
              .contains("List<List<Grid>> grid").contains("private Integer a;").contains("private Integer b;");
    }

    @Test @DisplayName("dates merge by kind: two LocalDates stay a LocalDate, a date and text become String")
    void datesMergeByKind() throws Exception {
        assertThat(java.fromJson("{\"ds\":[\"2024-01-01\",\"2024-02-02\"]}")).contains("List<LocalDate> ds");
        assertThat(java.fromJson("{\"ds\":[\"2024-01-01\",\"soon\"]}")).contains("List<String> ds");
    }

    @Test @DisplayName("JSON->Proto types a repeated message from every element")
    void protoUnionOfElements() throws Exception {
        assertThat(proto.jsonToProto("{\"items\":[{\"a\":1},{\"b\":\"x\"}]}"))
              .contains("int32 a = 1;").contains("string b = 2;");
    }

    @Test @DisplayName("Lombok's all-args constructor is left off a class with no fields")
    void lombokEmptyClass() throws Exception {
        // On a field-less class it is the no-args constructor again, declared
        // twice. Only a root can be one: an empty object below it is a map.
        String out = java.fromJson("{}", true);
        assertThat(out).contains("@NoArgsConstructor\npublic class Root").doesNotContain("@AllArgsConstructor");
        assertThat(java.fromJson("{\"e\":{}}", true)).contains("private Map<String, Object> e;")
              .contains("@AllArgsConstructor\npublic class Root");
    }

    @Test @DisplayName("a Kotlin class past the JVM parameter limit is rejected")
    void kotlinParameterLimitIsEnforced() throws Exception {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 300; i++) json.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1");
        json.append("}");
        assertThatThrownBy(() -> kotlin.fromJson(json.toString()))
              .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JVM parameter slots", "limit 255");
        assertThat(kotlin.fromJson("{\"a\":1}")).doesNotContain("exceed the JVM limit");
    }
}
