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
 * Tests for the optional Lombok-aware POJO generation mode.
 */
@DisplayName("JavaPojoGenerator – Lombok Mode")
class JavaPojoGeneratorLombokTest {

    private JavaPojoGenerator generator;

    @BeforeEach void setUp() { generator = new JavaPojoGenerator(); }

    @Test @DisplayName("Lombok mode annotates classes with @Data, @NoArgsConstructor, @AllArgsConstructor")
    void lombokAnnotationsPresent() throws Exception {
        String result = generator.fromJson("{\"name\":\"Alice\",\"age\":30}", true);
        assertThat(result)
              .contains("@Data\n@NoArgsConstructor\n@AllArgsConstructor\npublic class Root")
              .contains("import lombok.Data;")
              .contains("import lombok.NoArgsConstructor;")
              .contains("import lombok.AllArgsConstructor;");
    }

    @Test @DisplayName("Lombok mode annotates every generated class, including nested ones")
    void lombokOnNestedClasses() throws Exception {
        String result = generator.fromJson("{\"user\":{\"id\":1,\"email\":\"a@b.com\"}}", true);
        long annotated = result.lines().filter(l -> l.equals("@Data")).count();
        assertThat(annotated).isEqualTo(2); // Root + User
        assertThat(result).contains("public class Root").contains("public class User");
    }

    @Test @DisplayName("Default mode emits no Lombok annotations or imports")
    void defaultModeHasNoLombok() throws Exception {
        String result = generator.fromJson("{\"name\":\"Alice\"}");
        assertThat(result)
              .doesNotContain("@Data")
              .doesNotContain("lombok");
    }

    @Test @DisplayName("Explicit useLombok=false matches the default output")
    void explicitFalseMatchesDefault() throws Exception {
        String json = "{\"user\":{\"id\":1}}";
        assertThat(generator.fromJson(json, false)).isEqualTo(generator.fromJson(json));
    }

    @Test @DisplayName("Lombok mode keeps @JsonProperty for renamed fields")
    void lombokKeepsJsonProperty() throws Exception {
        String result = generator.fromJson("{\"first_name\":\"Alice\"}", true);
        assertThat(result)
              .contains("@JsonProperty(\"first_name\")")
              .contains("private String firstName");
    }

    @Test @DisplayName("XML input also supports Lombok mode")
    void lombokFromXml() throws Exception {
        String result = generator.fromXml("<root><name>Alice</name></root>", true);
        assertThat(result).contains("@Data").contains("public class Root");
    }
}
