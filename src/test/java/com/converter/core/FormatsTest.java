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
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class FormatsTest {
    @ParameterizedTest
    @CsvSource({"INPUT.JSON,JSON", "config.YML,YAML", "config.yaml,YAML", "data.csv,CSV",
          "settings.toml,TOML", "schema.proto,Protobuf", "document.xml,XML"})
    void recognizesInputAliasesAndCase(String file, String expected) {
        assertThat(Formats.inputForFileName(file)).isEqualTo(expected);
    }

    @Test void generatedFilesCannotBeOfferedAsInput() {
        assertThat(Formats.inputNames()).doesNotContain("Java POJO", "Kotlin", "JSON Schema");
        assertThat(Formats.inputForFileName("Root.java")).isNull();
        assertThat(Formats.inputForFileName("Root.kt")).isNull();
        assertThat(Formats.inputForFileName("schema.json")).isEqualTo("JSON");
        assertThat(Formats.outputsFor("Kotlin")).isEmpty();
        assertThat(Formats.outputsFor("unknown")).isEmpty();
    }

    @Test void everyAdvertisedTargetCanRenderAndHasAnExportName() throws Exception {
        var pipeline = new ConversionPipeline();
        for (String target : Formats.outputNames()) {
            assertThat(pipeline.renderFromJson("{\"name\":\"Ada\",\"age\":36}", target,
                  ConversionOptions.DEFAULTS)).as(target).isNotBlank();
            assertThat(ConversionFileNames.nameFor("example.json", target))
                  .endsWith("." + Formats.named(target).extension());
        }
        for (String input : Formats.inputNames()) {
            // Every output but the input itself, when the input is one.
            assertThat(Formats.outputsFor(input)).doesNotContain(input)
                  .hasSize(Formats.outputNames().length - (Formats.isOutput(input) ? 1 : 0));
        }
    }

    @Test void aProtobufPayloadIsReadButNeverWritten() {
        String payload = Formats.FMT_PROTO_PAYLOAD;
        assertThat(Formats.isInput(payload)).isTrue();
        assertThat(Formats.isOutput(payload)).isFalse();
        assertThat(Formats.inputNames()).contains(payload);
        assertThat(Formats.outputNames()).doesNotContain(payload);
        assertThat(Formats.outputsFor(payload)).containsExactly(Formats.outputNames());
        // Pasted as text, never opened as a file: no extension leads to it.
        assertThat(Formats.named(payload).extensions()).isEmpty();
        assertThat(Formats.named(payload).extension()).isEqualTo("txt");
        assertThat(Formats.inputForFileName("message.bin")).isNull();
        assertThat(Formats.inputForFileName("payload.txt")).isNull();
    }

    @Test void unknownNamesHaveNoInputCapability() {
        assertThat(Formats.inputForFileName(null)).isNull();
        assertThat(Formats.inputForFileName("README")).isNull();
        assertThat(Formats.isInput(null)).isFalse();
        assertThat(Formats.isInput("unknown")).isFalse();
        assertThat(ConversionFileNames.extensionFor("unknown")).isEqualTo("txt");
    }
}
