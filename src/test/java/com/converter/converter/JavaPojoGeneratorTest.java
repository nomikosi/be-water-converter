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

@DisplayName("JavaPojoGenerator")
class JavaPojoGeneratorTest {

    private JavaPojoGenerator generator;

    @BeforeEach void setUp() { generator = new JavaPojoGenerator(); }

    // ── from JSON ─────────────────────────────────────────────────────────

    @Test @DisplayName("JSON->POJO: simple flat object")
    void fromJsonFlat() throws Exception {
        String result = generator.fromJson("{\"name\":\"Alice\",\"age\":30}");
        assertThat(result)
              .contains("public class Root")
              .contains("private String name")
              .contains("private Integer age");
    }

    @Test @DisplayName("JSON->POJO: nested object generates child class")
    void fromJsonNested() throws Exception {
        String result = generator.fromJson("{\"user\":{\"id\":1,\"email\":\"a@b.com\"}}");
        assertThat(result)
              .contains("public class Root")
              .contains("public class User")
              .contains("private User user")
              .contains("private Integer id")
              .contains("private String email");
    }

    @Test @DisplayName("JSON->POJO: array of objects generates List field")
    void fromJsonArrayObjects() throws Exception {
        String result = generator.fromJson("{\"items\":[{\"id\":1,\"label\":\"A\"}]}");
        assertThat(result).containsIgnoringCase("List<")
              .contains("private Integer id")
              .contains("private String label");
    }

    @Test @DisplayName("JSON->POJO: array of primitives generates List")
    void fromJsonArrayPrimitives() throws Exception {
        assertThat(generator.fromJson("{\"scores\":[10,20,30]}")).contains("List");
    }

    @Test @DisplayName("JSON->POJO: root array uses first element")
    void fromJsonRootArray() throws Exception {
        String result = generator.fromJson("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
        assertThat(result)
              .contains("public class Root")
              .contains("private Integer id")
              .contains("private String name");
    }

    @Test @DisplayName("JSON->POJO: truncated JSON auto-closed")
    void fromJsonTruncated() throws Exception {
        String result = generator.fromJson("[{\"id\":1,\"name\":\"Alice\"");
        assertThat(result).contains("public class Root").contains("private Integer id");
    }

    @Test @DisplayName("JSON->POJO: complex menu JSON generates full class hierarchy")
    void fromJsonMenu() throws Exception {
        String input = "[{\"menu\":{\"id\":\"file\",\"value\":\"File\",\"popup\":{\"menuitem\":[{\"value\":\"New\",\"onclick\":\"CreateNewDoc()\"}]}}}]";
        String result = generator.fromJson(input);
        assertThat(result)
              .contains("public class Root")
              .contains("public class Menu")
              .contains("public class Popup")
              .contains("private String id")
              .contains("private String value")
              .contains("private String onclick");
    }

    @Test @DisplayName("JSON->POJO: boolean field maps to Boolean")
    void fromJsonBoolean() throws Exception {
        assertThat(generator.fromJson("{\"active\":true}")).contains("private Boolean active");
    }

    @Test @DisplayName("JSON->POJO: null field maps to Object")
    void fromJsonNull() throws Exception {
        assertThat(generator.fromJson("{\"unknown\":null}")).contains("private Object unknown");
    }

    @Test @DisplayName("JSON->POJO: long field maps to Long")
    void fromJsonLong() throws Exception {
        assertThat(generator.fromJson("{\"bigNum\":9999999999}")).contains("private Long bigNum");
    }

    @Test @DisplayName("JSON->POJO: snake_case field names converted to camelCase")
    void fromJsonSnakeCase() throws Exception {
        String result = generator.fromJson("{\"first_name\":\"Alice\",\"last_name\":\"Smith\"}");
        assertThat(result)
              .contains("private String firstName")
              .contains("private String lastName");
    }

    @Test @DisplayName("JSON->POJO: empty array generates List")
    void fromJsonEmptyArray() throws Exception {
        assertThat(generator.fromJson("{\"items\":[]}")).contains("List");
    }

    @Test @DisplayName("JSON->POJO: deeply nested objects all generate classes")
    void fromJsonDeepNested() throws Exception {
        String input = "{\"level1\":{\"level2\":{\"level3\":{\"value\":\"deep\"}}}}";
        String result = generator.fromJson(input);
        assertThat(result)
              .contains("public class Root")
              .contains("public class Level1")
              .contains("public class Level2")
              .contains("public class Level3")
              .contains("private String value");
    }

    @Test @DisplayName("JSON->POJO: double field maps to Double")
    void fromJsonDouble() throws Exception {
        assertThat(generator.fromJson("{\"price\":19.99}")).contains("private Double price");
    }

    // ── from XML ──────────────────────────────────────────────────────────

    @Test @DisplayName("XML->POJO: simple flat XML")
    void fromXmlFlat() throws Exception {
        String result = generator.fromXml("<root><name>Alice</name><age>30</age></root>");
        assertThat(result).contains("public class Root").contains("private String name");
    }

    @Test @DisplayName("XML->POJO: nested XML generates child class")
    void fromXmlNested() throws Exception {
        String result = generator.fromXml("<root><user><id>1</id><email>a@b.com</email></user></root>");
        assertThat(result)
              .contains("public class Root")
              .contains("public class User")
              .contains("private String email");
    }
}
