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

package com.converter;

import com.converter.converter.ConversionPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scratch-file naming for conversion results")
class ConverterScratchFilesTest {

    @Test @DisplayName("each output format maps to its own extension")
    void extensions() {
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_JSON)).isEqualTo("json");
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_XML)).isEqualTo("xml");
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_YAML)).isEqualTo("yaml");
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_CSV)).isEqualTo("csv");
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_TOML)).isEqualTo("toml");
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_PROTO)).isEqualTo("proto");
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_JAVA)).isEqualTo("java");
        // A JSON Schema document is still JSON.
        assertThat(ConverterScratchFiles.extensionFor(ConversionPipeline.FMT_SCHEMA)).isEqualTo("json");
    }

    @Test @DisplayName("unknown format falls back to .txt rather than failing")
    void unknownFormat() {
        assertThat(ConverterScratchFiles.extensionFor("Nonsense")).isEqualTo("txt");
    }

    @Test @DisplayName("the source file's base name is kept so results stay tellable apart")
    void keepsBaseName() {
        assertThat(ConverterScratchFiles.scratchNameFor("customers.csv", ConversionPipeline.FMT_JSON))
              .isEqualTo("customers.json");
        assertThat(ConverterScratchFiles.scratchNameFor("schema.proto", ConversionPipeline.FMT_YAML))
              .isEqualTo("schema.yaml");
    }

    @Test @DisplayName("a nameless source (editor selection) gets a generic name")
    void namelessSource() {
        assertThat(ConverterScratchFiles.scratchNameFor(null, ConversionPipeline.FMT_XML))
              .isEqualTo("converted.xml");
        assertThat(ConverterScratchFiles.scratchNameFor("  ", ConversionPipeline.FMT_XML))
              .isEqualTo("converted.xml");
    }

    @Test @DisplayName("names with several dots keep everything before the last one")
    void multipleDots() {
        assertThat(ConverterScratchFiles.scratchNameFor("my.data.v2.json", ConversionPipeline.FMT_CSV))
              .isEqualTo("my.data.v2.csv");
    }

    @Test @DisplayName("a dotfile is not mistaken for an extension")
    void dotfile() {
        // lastIndexOf('.') is 0 here; treating that as an extension would yield
        // a file called ".json" with an empty base name.
        assertThat(ConverterScratchFiles.scratchNameFor(".gitignore", ConversionPipeline.FMT_JSON))
              .isEqualTo(".gitignore.json");
    }
}
