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

@DisplayName("Conversion result file naming")
class ConversionFileNamesTest {

    @Test @DisplayName("each output format maps to its own extension")
    void extensions() {
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_JSON)).isEqualTo("json");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_XML)).isEqualTo("xml");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_YAML)).isEqualTo("yaml");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_CSV)).isEqualTo("csv");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_TOML)).isEqualTo("toml");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_PROTO)).isEqualTo("proto");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_JAVA)).isEqualTo("java");
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_KOTLIN)).isEqualTo("kt");
        // A JSON Schema document is still JSON.
        assertThat(ConversionFileNames.extensionFor(Formats.FMT_SCHEMA)).isEqualTo("json");
    }

    @Test @DisplayName("unknown format falls back to .txt rather than failing")
    void unknownFormat() {
        assertThat(ConversionFileNames.extensionFor("Nonsense")).isEqualTo("txt");
    }

    @Test @DisplayName("Java results are always Root.java, matching the generated class")
    void javaAlwaysRootJava() {
        // IntelliJ flags CLASS_WRONG_FILE_NAME when a public class and its file
        // disagree, and the generator always emits "public class Root".
        assertThat(ConversionFileNames.nameFor("customers.csv", Formats.FMT_JAVA))
              .isEqualTo("Root.java");
        assertThat(ConversionFileNames.nameFor(null, Formats.FMT_JAVA))
              .isEqualTo("Root.java");
    }

    @Test @DisplayName("Kotlin keeps the source name, and falls back to Root.kt without one")
    void kotlinNaming() {
        // Kotlin allows several top-level declarations per file, so unlike Java
        // the file name is free to follow the source.
        assertThat(ConversionFileNames.nameFor("customers.csv", Formats.FMT_KOTLIN))
              .isEqualTo("customers.kt");
        assertThat(ConversionFileNames.nameFor(null, Formats.FMT_KOTLIN))
              .isEqualTo("Root.kt");
        assertThat(ConversionFileNames.nameFor("  ", Formats.FMT_KOTLIN))
              .isEqualTo("Root.kt");
    }

    @Test @DisplayName("the source file's base name is kept so results stay tellable apart")
    void keepsBaseName() {
        assertThat(ConversionFileNames.nameFor("customers.csv", Formats.FMT_JSON))
              .isEqualTo("customers.json");
        assertThat(ConversionFileNames.nameFor("schema.proto", Formats.FMT_YAML))
              .isEqualTo("schema.yaml");
    }

    @Test @DisplayName("a nameless source (editor selection) gets a generic name")
    void namelessSource() {
        assertThat(ConversionFileNames.nameFor(null, Formats.FMT_XML))
              .isEqualTo("converted.xml");
        assertThat(ConversionFileNames.nameFor("  ", Formats.FMT_XML))
              .isEqualTo("converted.xml");
    }

    @Test @DisplayName("names with several dots keep everything before the last one")
    void multipleDots() {
        assertThat(ConversionFileNames.nameFor("my.data.v2.json", Formats.FMT_CSV))
              .isEqualTo("my.data.v2.csv");
    }

    @Test @DisplayName("a dotfile is not mistaken for an extension")
    void dotfile() {
        // lastIndexOf('.') is 0 here; treating that as an extension would yield
        // a file called ".json" with an empty base name.
        assertThat(ConversionFileNames.nameFor(".gitignore", Formats.FMT_JSON))
              .isEqualTo(".gitignore.json");
    }
}
