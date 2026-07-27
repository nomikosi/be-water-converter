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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("KotlinDataClassGenerator")
class KotlinDataClassGeneratorTest {

    private KotlinDataClassGenerator generator;

    @BeforeEach void setUp() { generator = new KotlinDataClassGenerator(); }

    @Test @DisplayName("a flat object becomes a data class with val properties")
    void flatObject() throws Exception {
        assertThat(generator.fromJson("{\"name\":\"Ada\",\"age\":30}"))
              .contains("data class Root(")
              .contains("val name: String")
              .contains("val age: Int");
    }

    @Test @DisplayName("every class is top-level and public — Kotlin allows several per file")
    void allClassesTopLevel() throws Exception {
        String out = generator.fromJson("{\"owner\":{\"name\":\"a\"}}");
        // Unlike the Java output, nothing needs to be package-private here.
        assertThat(out).contains("data class Root(").contains("data class Owner(");
        assertThat(out).doesNotContain("class Root {").doesNotContain("private ");
    }

    @Test @DisplayName("scalar types map to Kotlin equivalents")
    void scalarTypes() throws Exception {
        String out = generator.fromJson(
              "{\"i\":1,\"l\":9999999999,\"d\":1.5,\"b\":true,\"s\":\"x\"}");
        assertThat(out)
              .contains("val i: Int")
              .contains("val l: Long")
              .contains("val d: Double")
              .contains("val b: Boolean")
              .contains("val s: String");
    }

    @Test @DisplayName("a null example yields a nullable Any?")
    void nullBecomesNullableAny() throws Exception {
        // An example can only show what was present; null is the one case where
        // it positively demonstrates nullability.
        assertThat(generator.fromJson("{\"nothing\":null}")).contains("val nothing: Any?");
    }

    @Test @DisplayName("arrays become List, empty arrays List<Any>")
    void arrays() throws Exception {
        assertThat(generator.fromJson("{\"xs\":[1,2]}")).contains("val xs: List<Int>");
        assertThat(generator.fromJson("{\"xs\":[]}")).contains("val xs: List<Any>");
        assertThat(generator.fromJson("{\"xs\":[{\"k\":1}]}"))
              .contains("val xs: List<Xs>")
              .contains("data class Xs(");
    }

    @Test @DisplayName("an empty object is a plain class, since a data class needs a parameter")
    void emptyObjectIsPlainClass() throws Exception {
        String out = generator.fromJson("{\"meta\":{}}");
        assertThat(out).contains("class Meta").doesNotContain("data class Meta");
    }

    @Test @DisplayName("Kotlin hard keywords are renamed and mapped back with @JsonProperty")
    void keywordsRenamed() throws Exception {
        String out = generator.fromJson("{\"when\":1,\"class\":2,\"is\":true,\"fun\":\"f\"}");
        assertThat(out)
              .contains("val whenValue: Int")
              .contains("val classValue: Int")
              .contains("val isValue: Boolean")
              .contains("val funValue: String")
              .contains("@JsonProperty(\"when\")")
              .contains("@JsonProperty(\"class\")");
    }

    @Test @DisplayName("soft keywords are left alone — they are legal property names")
    void softKeywordsUntouched() throws Exception {
        assertThat(generator.fromJson("{\"data\":1,\"value\":2,\"sealed\":3}"))
              .contains("val data: Int")
              .contains("val value: Int")
              .contains("val sealed: Int");
    }

    @Test @DisplayName("a leading digit is prefixed")
    void leadingDigit() throws Exception {
        assertThat(generator.fromJson("{\"2legit\":1}"))
              .contains("val _2legit: Int")
              .contains("@JsonProperty(\"2legit\")");
    }

    @Test @DisplayName("snake_case keys become camelCase with @JsonProperty")
    void snakeCase() throws Exception {
        assertThat(generator.fromJson("{\"first_name\":\"a\"}"))
              .contains("val firstName: String")
              .contains("@JsonProperty(\"first_name\")");
    }

    @Test @DisplayName("dates are detected, and the toggle turns it off")
    void dateDetection() throws Exception {
        assertThat(generator.fromJson("{\"d\":\"2026-07-27\"}", true))
              .contains("val d: LocalDate").contains("import java.time.LocalDate");
        assertThat(generator.fromJson("{\"d\":\"2026-07-27T10:15:30+01:00\"}", true))
              .contains("val d: OffsetDateTime");
        assertThat(generator.fromJson("{\"d\":\"2026-07-27\"}", false))
              .contains("val d: String").doesNotContain("import java.time");
        // Confirmed with a real parse, so a well-formed-looking non-date stays text.
        assertThat(generator.fromJson("{\"d\":\"2025-13-99\"}", true)).contains("val d: String");
    }

    @Test @DisplayName("imports are emitted only when used")
    void conditionalImports() throws Exception {
        assertThat(generator.fromJson("{\"n\":\"x\"}"))
              .doesNotContain("import")
              .startsWith("data class Root(");
    }

    @Test @DisplayName("colliding class names get distinct classes, as in the Java generator")
    void collidingClassNames() throws Exception {
        // Shared with JavaPojoGenerator through StructureModel, so the two
        // generators cannot drift apart on collision handling.
        String out = generator.fromJson("{\"user\":{\"x\":1},\"outer\":{\"user\":{\"y\":\"s\"}}}");
        assertThat(out)
              .contains("data class User(")
              .contains("data class User2(")
              .contains("val user: User2");
    }

    @Test @DisplayName("a root array is typed from its first element")
    void rootArray() throws Exception {
        assertThat(generator.fromJson("[{\"id\":1},{\"id\":2}]")).contains("val id: Int");
    }

    @Test @DisplayName("blank and empty-array input are rejected")
    void rejectsUnusableInput() {
        assertThatThrownBy(() -> generator.fromJson("  "))
              .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.fromJson("[]"))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("a quote or dollar in a key is escaped inside @JsonProperty")
    void escapesInAnnotation() throws Exception {
        // An unescaped $ would be a Kotlin string template and fail to compile.
        assertThat(generator.fromJson("{\"a$b\":1}")).contains("@JsonProperty(\"a\\$b\")");
    }

    @Test @DisplayName("reachable as a pipeline output format")
    void viaPipeline() throws Exception {
        assertThat(new ConversionPipeline().renderFromJson("{\"a\":1}",
              ConversionPipeline.FMT_KOTLIN, ConversionOptions.DEFAULTS))
              .contains("data class Root(");
    }

    @Test @DisplayName("trailing commas are correct — the last property has none")
    void noTrailingComma() throws Exception {
        String out = generator.fromJson("{\"a\":1,\"b\":2}");
        // A trailing comma before ')' is legal in modern Kotlin but ugly; more
        // importantly this pins that the separator logic tracks the last entry.
        assertThat(out).contains("val b: Int\n)");
        assertThat(out).doesNotContain("val b: Int,\n)");
    }
}
