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

import com.converter.ContextDecisions.Kind;
import com.converter.core.CsvConverter;
import com.converter.core.Formats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the editor and Project-view actions decide, without the IDE around them. */
@DisplayName("Context menu decisions")
class ContextDecisionsTest {

    private static final String JAVA =
          "package a;\n\nclass A {\n    int x;\n    A(int x) {\n        this.x = x;\n    }\n}\n";

    @Test @DisplayName("a data file is offered by its extension or by its IDE file type")
    void dataFilesAreOffered() {
        assertThat(ContextDecisions.offer("a.json", Kind.OTHER, null, "not json at all")).isTrue();
        assertThat(ContextDecisions.offer("a.tsv", Kind.PLAIN, null, null)).isTrue();
        assertThat(ContextDecisions.offer("icon.svg", Kind.DATA, null, "<svg/>")).isTrue();
    }

    @Test @DisplayName("a file in another language is offered only for a selection that reads as a format")
    void otherLanguagesNeedASelection() {
        // Each of these was "detected" as TOML or YAML, and converting it failed.
        assertThat(ContextDecisions.offer("A.java", Kind.OTHER, null, JAVA)).isFalse();
        assertThat(ContextDecisions.offer("run.sh", Kind.OTHER, null, "#!/bin/sh\nVERSION=1.2\n")).isFalse();
        assertThat(ContextDecisions.offer("README.md", Kind.OTHER, null, "# Title\n\nNote: prose.\n")).isFalse();
        assertThat(ContextDecisions.offer("gradle.properties", Kind.OTHER, null, "org.gradle.caching=true\n")).isFalse();
        assertThat(ContextDecisions.offer("A.java", Kind.OTHER, "{\"a\": 1}", JAVA)).isTrue();
        assertThat(ContextDecisions.offer("A.java", Kind.OTHER, "just words", JAVA)).isFalse();
    }

    @Test @DisplayName("plain text and unknown files are offered by their content")
    void plainTextIsSniffed() {
        assertThat(ContextDecisions.offer("notes.txt", Kind.PLAIN, null, "{\"a\": 1}")).isTrue();
        assertThat(ContextDecisions.offer(null, Kind.PLAIN, null, "just some words")).isFalse();
    }

    @Test @DisplayName("the IDE's file types are sorted by name into data, plain text and other languages")
    void kinds() {
        for (String data : new String[]{"JSON", "JSON5", "XML", "YAML", "TOML", "CSV", "TSV", "protobuf", "SVG", "XHTML"})
            assertThat(ContextDecisions.kindOf(data, false)).as(data).isEqualTo(Kind.DATA);
        // HTML is rarely well-formed XML: it gets the actions only for a selection that reads as a format.
        for (String other : new String[]{"JAVA", "Python", "Markdown", "Properties", "Shell Script", "HTML"})
            assertThat(ContextDecisions.kindOf(other, false)).as(other).isEqualTo(Kind.OTHER);
        assertThat(ContextDecisions.kindOf("PLAIN_TEXT", true)).isEqualTo(Kind.PLAIN);
        assertThat(ContextDecisions.kindOf(null, false)).isEqualTo(Kind.PLAIN);
    }

    @Test @DisplayName("formats and delimiters come from the extension first, then from the start of the text")
    void formatsAndDelimiters() {
        assertThat(ContextDecisions.formatFor("x.yaml", "{\"a\":1}")).isEqualTo(Formats.FMT_YAML);
        // No extension of theirs is mapped: the content says XML.
        assertThat(ContextDecisions.formatFor("icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><g/></svg>"))
              .isEqualTo(Formats.FMT_XML);
        assertThat(ContextDecisions.formatFor("page.xhtml",
              "<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><body/></html>")).isEqualTo(Formats.FMT_XML);
        String large = "{\"a\": \"" + "x".repeat(200_000) + "\"}";
        assertThat(ContextDecisions.formatFor(null, large)).isEqualTo(Formats.FMT_JSON);
        assertThat(ContextDecisions.delimiterFor(null, "a;b\n1;2\n")).isEqualTo(';');
        // A .tsv file too short to tell is still tab-separated.
        assertThat(ContextDecisions.delimiterFor("data.tsv", "single")).isEqualTo('\t');
        assertThat(ContextDecisions.delimiterFor("data.csv", "single")).isNull();
        assertThat(ContextDecisions.csvFormatFor("data.csv", "single", CsvConverter.CsvFormat.SEMICOLON))
              .isEqualTo(CsvConverter.CsvFormat.SEMICOLON);
    }
}
