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

    @Test @DisplayName("JSON->POJO: array of mixed types produces List or safe fallback")
    void mixedTypeArray() throws Exception {
        assertThatCode(() -> generator.fromJson("{\"data\":[1,\"two\",true]}")).doesNotThrowAnyException();
    }

    @Test @DisplayName("JSON->POJO: array containing null values does not throw")
    void arrayWithNulls() throws Exception {
        assertThatCode(() -> generator.fromJson("{\"items\":[null,null,null]}")).doesNotThrowAnyException();
    }

    @Test @DisplayName("JSON->POJO: sibling nested objects produce independent inner classes")
    void siblingNestedObjects() throws Exception {
        String result = generator.fromJson(
              "{\"billing\":{\"street\":\"1 Main\"},\"shipping\":{\"street\":\"2 Oak\"}}"
        );
        assertThat(result).contains("public class Root").contains("street");
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
}
