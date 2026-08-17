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
 * Edge-case tests for JavaPojoGenerator.
 * Covers: reserved keywords, hyphenated/numeric field names, mixed array types,
 * empty nested objects, unicode field names, real-world API shapes, and
 * @JsonProperty annotation on renamed fields.
 */
@DisplayName("JavaPojoGenerator – Edge Cases")
class JavaPojoGeneratorEdgeCaseTest {

    private JavaPojoGenerator generator;

    @BeforeEach void setUp() { generator = new JavaPojoGenerator(); }

    // ── Reserved Java keywords as field names ─────────────────────────────

    @Test @DisplayName("JSON->POJO: \'class\' as field name — output is valid Java identifier")
    void reservedWordClass() throws Exception {
        String result = generator.fromJson("{\"class\":\"premium\",\"id\":1}");
        assertThat(result).doesNotContain("private String class ");
    }

    @Test @DisplayName("JSON->POJO: \'return\' as field name — sanitised or renamed")
    void reservedWordReturn() throws Exception {
        assertThatCode(() -> generator.fromJson("{\"return\":42}")).doesNotThrowAnyException();
    }

    @Test @DisplayName("JSON->POJO: \'int\' as field name — sanitised or renamed")
    void reservedWordInt() throws Exception {
        assertThatCode(() -> generator.fromJson("{\"int\":5}")).doesNotThrowAnyException();
    }

    // ── Name collisions and sanitization (v1.4.0 regressions) ────────────

    @Test @DisplayName("JSON->POJO: keys normalising to the same field name are deduplicated")
    void collidingFieldNamesDeduplicated() throws Exception {
        String result = generator.fromJson("{\"user_name\":\"a\",\"userName\":\"b\"}");
        assertThat(result).contains("private String userName;")
              .contains("private String userName2;");
    }

    @Test @DisplayName("JSON->POJO: key starting with a digit becomes a valid identifier")
    void digitLeadingKey() throws Exception {
        String result = generator.fromJson("{\"1st_place\":\"gold\"}");
        assertThat(result).doesNotContain("private String 1st");
        assertThat(result).contains("private String _1stPlace;");
    }

    @Test @DisplayName("JSON->POJO: invalid characters in every segment are sanitized")
    void invalidCharsInLaterSegments() throws Exception {
        String result = generator.fromJson("{\"foo.bar!\":1}");
        // The @JsonProperty annotation keeps the original key; the field name must be clean.
        assertThat(result).contains("private Integer fooBar_;");
        assertThat(result).contains("@JsonProperty(\"foo.bar!\")");
    }

    @Test @DisplayName("JSON->POJO: empty array maps to List<Object>, not List<?>")
    void emptyArrayIsListObject() throws Exception {
        String result = generator.fromJson("{\"items\":[]}");
        assertThat(result).contains("List<Object> items").doesNotContain("List<?>");
    }

    @Test @DisplayName("JSON->POJO: integer beyond Long range maps to BigInteger")
    void bigIntegerSupported() throws Exception {
        String result = generator.fromJson("{\"id\":99999999999999999999999999}");
        assertThat(result).contains("BigInteger id").contains("import java.math.BigInteger;");
    }

    // ── ISO-8601 date detection (v1.4.0) ──────────────────────────────────

    @Test @DisplayName("JSON->POJO: ISO date string maps to LocalDate with import")
    void isoDateDetected() throws Exception {
        String result = generator.fromJson("{\"birthday\":\"1990-05-17\"}");
        assertThat(result).contains("private LocalDate birthday;")
              .contains("import java.time.LocalDate;");
    }

    @Test @DisplayName("JSON->POJO: ISO datetime with offset maps to OffsetDateTime")
    void isoOffsetDateTimeDetected() throws Exception {
        String result = generator.fromJson("{\"created\":\"2025-01-02T03:04:05Z\"}");
        assertThat(result).contains("private OffsetDateTime created;")
              .contains("import java.time.OffsetDateTime;");
    }

    @Test @DisplayName("JSON->POJO: ISO datetime without offset maps to LocalDateTime")
    void isoLocalDateTimeDetected() throws Exception {
        String result = generator.fromJson("{\"updated\":\"2025-01-02T03:04:05\"}");
        assertThat(result).contains("private LocalDateTime updated;")
              .contains("import java.time.LocalDateTime;");
    }

    @Test @DisplayName("JSON->POJO: impossible date like 2025-13-99 stays String")
    void impossibleDateStaysString() throws Exception {
        String result = generator.fromJson("{\"code\":\"2025-13-99\"}");
        assertThat(result).contains("private String code;")
              .doesNotContain("java.time");
    }

    @Test @DisplayName("JSON->POJO: date detection can be disabled")
    void dateDetectionDisabled() throws Exception {
        String result = generator.fromJson("{\"birthday\":\"1990-05-17\"}", false, false);
        assertThat(result).contains("private String birthday;")
              .doesNotContain("LocalDate");
    }

    @Test @DisplayName("JSON->POJO: no java.time imports when no dates are present")
    void noDateImportsWithoutDates() throws Exception {
        String result = generator.fromJson("{\"name\":\"Alice\"}");
        assertThat(result).doesNotContain("java.time");
    }

    @Test @DisplayName("JSON->POJO: array of ISO dates maps to List<LocalDate>")
    void arrayOfDates() throws Exception {
        String result = generator.fromJson("{\"holidays\":[\"2025-12-25\",\"2026-01-01\"]}");
        assertThat(result).contains("private List<LocalDate> holidays;");
    }

    // ── Field name transformations ────────────────────────────────────────

    @Test @DisplayName("JSON->POJO: kebab-case field converted to camelCase")
    void kebabCaseField() throws Exception {
        String result = generator.fromJson("{\"first-name\":\"Alice\",\"last-name\":\"Smith\"}");
        assertThat(result).contains("firstName").contains("lastName");
        assertThat(result).doesNotContain("private String first-name");
    }

    @Test @DisplayName("JSON->POJO: snake_case field has @JsonProperty with original name")
    void snakeCaseJsonProperty() throws Exception {
        String result = generator.fromJson("{\"first_name\":\"Alice\",\"last_name\":\"Smith\"}");
        assertThat(result).contains("@JsonProperty").contains("first_name");
    }

    @Test @DisplayName("JSON->POJO: kebab-case field has @JsonProperty with original name")
    void kebabCaseJsonProperty() throws Exception {
        String result = generator.fromJson("{\"phone-number\":\"123-456\"}");
        assertThat(result).contains("@JsonProperty").contains("phone-number");
    }

    @Test @DisplayName("JSON->POJO: field starting with digit is prefixed or renamed")
    void numericStartingField() throws Exception {
        assertThatCode(() -> generator.fromJson("{\"2fast\":true}")).doesNotThrowAnyException();
    }

    // ── Structural edge cases ─────────────────────────────────────────────

    @Test @DisplayName("JSON->POJO: empty nested object {} still generates inner class")
    void emptyNestedObject() throws Exception {
        assertThat(generator.fromJson("{\"meta\":{}}")).contains("meta");
    }

    @Test @DisplayName("JSON->POJO: a mixed array is typed from its first element")
    void mixedTypeArray() throws Exception {
        // Pinning deliberate behaviour, not endorsing it: the POJO generator
        // samples element 0 while JsonSchemaGenerator emits anyOf for the same
        // input. Only one of those positions was recorded by a test before.
        // The generated List<Integer> cannot deserialize the document it came
        // from; widening it is a behaviour decision, not a bug fix.
        assertThat(generator.fromJson("{\"data\":[1,\"two\",true]}"))
              .contains("private List<Integer> data;");
    }

    @Test @DisplayName("JSON->POJO: an all-null array falls back to List<Object>")
    void arrayWithNulls() throws Exception {
        assertThat(generator.fromJson("{\"items\":[null,null,null]}"))
              .contains("private List<Object> items;");
    }

    @Test @DisplayName("JSON->POJO: identically-shaped siblings still get their own classes")
    void siblingNestedObjects() throws Exception {
        // The values must be IDENTICAL for this to be capable of failing: with
        // differing values the old name-keyed collection produced the right
        // answer by accident.
        String result = generator.fromJson(
              "{\"billing\":{\"street\":\"1 Main\"},\"shipping\":{\"street\":\"1 Main\"}}"
        );
        assertThat(result)
              .contains("public class Root")
              .contains("private Billing billing;")
              .contains("private Shipping shipping;")
              .contains("\nclass Billing")
              .contains("\nclass Shipping");
    }

    @Test @DisplayName("JSON->POJO: an array of arrays of objects still emits the element class")
    void nestedArraysOfObjects() throws Exception {
        String result = generator.fromJson("{\"matrix\":[[{\"a\":1}]]}");
        assertThat(result)
              .contains("private List<List<Matrix>> matrix;")
              .contains("class Matrix");
    }

    // ── Keys that are not valid identifiers or literals ───────────────────

    @Test @DisplayName("JSON->POJO: a quote or backslash in a key is escaped in @JsonProperty")
    void escapesInAnnotation() throws Exception {
        // Written raw, these ended the string literal and the class stopped compiling.
        assertThat(generator.fromJson("{\"a\\\"b\":1}")).contains("@JsonProperty(\"a\\\"b\")");
        assertThat(generator.fromJson("{\"c:\\\\path\":1}")).contains("@JsonProperty(\"c:\\\\path\")");
        assertThat(generator.fromJson("{\"a\\nb\":1}"))
              .contains("@JsonProperty(\"a\\nb\")")
              .doesNotContain("@JsonProperty(\"a\n");
    }

    @Test @DisplayName("JSON->POJO: keys that sanitise to nothing still get a legal identifier")
    void unusableKeysGetLegalNames() throws Exception {
        // "_" has been a reserved keyword since Java 9, so it cannot be the fallback.
        assertThat(generator.fromJson("{\"\":1}")).contains("private Integer _value;");
        assertThat(generator.fromJson("{\"@\":1}")).contains("private Integer _value;");
        assertThat(generator.fromJson("{\"__\":1}")).contains("private Integer _value;");
        // The same rule applies where the name becomes a class rather than a field.
        assertThat(generator.fromJson("{\"\":{\"a\":1}}"))
              .contains("private _Value _value;")
              .contains("class _Value");
    }

    @Test @DisplayName("JSON->POJO: a root array of arrays is unwrapped all the way down")
    void rootArrayOfArrays() throws Exception {
        // The same shape one level down generates; the root must not disagree.
        assertThat(generator.fromJson("[[{\"id\":1}]]")).contains("private Integer id;");
    }

    // ── Generated names must not collide with the types we emit ───────────

    @Test @DisplayName("JSON->POJO: a class cannot take the name of an emitted import")
    void classNameCannotShadowImport() throws Exception {
        // "class List" beside "import java.util.List;" is "already defined in
        // this compilation unit", so the whole file stopped compiling.
        assertThat(generator.fromJson("{\"list\":{\"a\":1},\"items\":[1,2]}"))
              .contains("import java.util.List;")
              .contains("class ListValue")
              .contains("private ListValue list;")
              .contains("private List<Integer> items;");

        assertThat(generator.fromJson("{\"json_property\":{\"a\":1}}"))
              .contains("class JsonPropertyValue");
    }

    @Test @DisplayName("JSON->POJO: the Lombok names are reserved only in Lombok mode")
    void lombokNamesReservedOnlyWithLombok() throws Exception {
        // "data" is a common key. Reserving Data unconditionally renamed its
        // class for every document, including the ones emitting no Lombok import.
        assertThat(generator.fromJson("{\"data\":{\"a\":1}}", true))
              .contains("import lombok.Data;")
              .contains("class DataValue");
        assertThat(generator.fromJson("{\"data\":{\"a\":1}}", false))
              .doesNotContain("import lombok")
              .contains("class Data ")
              .contains("private Data data;");
    }

    @Test @DisplayName("JSON->POJO: the boolean and null literals are not identifiers either")
    void literalsAreRenamed() throws Exception {
        // JLS 3.8 bars true/false/null from identifiers, so "private Integer
        // false;" does not parse. The Kotlin generator already listed all three.
        assertThat(generator.fromJson("{\"false\":1,\"true\":2,\"null\":3}"))
              .contains("private Integer falseValue;")
              .contains("private Integer trueValue;")
              .contains("private Integer nullValue;")
              .contains("@JsonProperty(\"false\")");
    }

    @Test @DisplayName("JSON->POJO: a class cannot shadow a java.lang type it uses")
    void classNameCannotShadowJavaLang() throws Exception {
        // "class String" compiles, which is worse: every String field in the
        // file would silently refer to the generated class instead.
        assertThat(generator.fromJson("{\"string\":{\"a\":1},\"name\":\"x\"}"))
              .contains("class StringValue")
              .contains("private StringValue string;")
              .contains("private String name;");
    }

    @Test @DisplayName("JSON->POJO: an empty key gets a note, not a @JsonProperty that cannot work")
    void emptyKeyIsNotAnnotated() throws Exception {
        // Jackson reads @JsonProperty("") as USE_DEFAULT_NAME, so the annotation
        // bound to "_value" rather than "" and the class could not read the
        // document it came from. Emitting it claimed a mapping that never existed.
        // Matched as the annotation form: the explanatory note names it too.
        assertThat(generator.fromJson("{\"\":1}"))
              .contains("// source key is empty")
              .contains("private Integer _value;")
              .doesNotContain("@JsonProperty(")
              .doesNotContain("import com.fasterxml.jackson");

        // The usual arrival route: XmlMapper files element text content under "".
        assertThat(generator.fromXml("<root>hello</root>"))
              .contains("// source key is empty")
              .doesNotContain("@JsonProperty(");

        // A key that merely needs renaming is still annotated as before.
        assertThat(generator.fromJson("{\"first_name\":\"a\"}"))
              .contains("@JsonProperty(\"first_name\")")
              .contains("import com.fasterxml.jackson.annotation.JsonProperty;");
    }

    @Test @DisplayName("JSON->POJO: a real key wanting the substituted name is still deduplicated")
    void reservedSubstituteStillDeduplicated() throws Exception {
        // Order decides, as it already does for User/User2: "list" is substituted
        // to ListValue first, so the key that actually spells ListValue takes
        // ListValue2. Pinned so which class holds which fields is not ambiguous.
        String result = generator.fromJson(
              "{\"list\":{\"a\":1},\"list_value\":{\"b\":2},\"items\":[1,2]}");
        assertThat(result)
              .contains("private ListValue list;")
              .contains("private ListValue2 listValue;")
              .containsSubsequence("class ListValue {", "private Integer a;")
              .containsSubsequence("class ListValue2 {", "private Integer b;");
    }

    // ── Null / blank input ────────────────────────────────────────────────

    @Test @DisplayName("JSON->POJO: null input throws")
    void fromJsonNullInput() {
        assertThatThrownBy(() -> generator.fromJson(null)).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("JSON->POJO: blank input throws")
    void fromJsonBlankInput() {
        assertThatThrownBy(() -> generator.fromJson("   ")).isInstanceOf(Exception.class);
    }

    @Test @DisplayName("JSON->POJO: a scalar document is rejected rather than generating nothing")
    void rejectsScalarRoot() {
        assertThatThrownBy(() -> generator.fromJson("42"))
              .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.fromJson("[1,2]"))
              .isInstanceOf(IllegalArgumentException.class);
    }
}
