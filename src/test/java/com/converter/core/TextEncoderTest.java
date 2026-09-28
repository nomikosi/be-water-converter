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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Saved text in the style of the file it replaces")
class TextEncoderTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF8_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static byte[] withMark(byte[] mark, byte[] body) {
        byte[] bytes = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, bytes, 0, mark.length);
        System.arraycopy(body, 0, bytes, mark.length, body.length);
        return bytes;
    }

    @Test @DisplayName("a new file is UTF-8 with the line separator for new files")
    void newFile() {
        TextEncoder.Encoded saved = TextEncoder.forSave("a,b\nCafé,1\n", Formats.FMT_CSV, null, null, "\r\n");
        assertThat(saved.bytes()).isEqualTo("a,b\r\nCafé,1\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(saved.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(saved.refused()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    @DisplayName("CSV saves change row separators without rewriting quoted cells or headers")
    void csvCellLineBreaks(String separator) throws Exception {
        CsvConverter csv = new CsvConverter();
        for (CsvConverter.CsvFormat format : new CsvConverter.CsvFormat[]{
              CsvConverter.CsvFormat.DEFAULT, CsvConverter.CsvFormat.SEMICOLON, CsvConverter.CsvFormat.TAB}) {
            String header = "\"head\nline\"" + format.delimiter() + "note";
            String row = "\"say \"\"hi\"\"\"" + format.delimiter() + "\"one\ntwo\r\nthree\rfour\"";
            String input = header + "\n" + row + "\n";
            String expected = header + separator + row + separator;
            byte[] head = expected.getBytes(StandardCharsets.UTF_8);
            // Both a new file and an existing file whose first newline is inside its header.
            for (byte[] existing : new byte[][]{null, head}) {
                String fallback = existing == null ? separator : "\n";
                TextEncoder.Encoded saved = TextEncoder.forSave(input, Formats.FMT_CSV, existing,
                      StandardCharsets.UTF_8, fallback);
                String text = new String(saved.bytes(), saved.charset());
                assertThat(text).isEqualTo(expected);
                assertThat(csv.csvToJson(text, false, format)).isEqualTo(csv.csvToJson(input, false, format));
            }
        }
    }

    @Test @DisplayName("a quote inside an unquoted cell of the replaced file does not hide its CRLF")
    void literalQuoteInReplacedFile() {
        byte[] head = "size (\"),name\r\n12,pizza\r\n".getBytes(StandardCharsets.UTF_8);
        TextEncoder.Encoded saved = TextEncoder.forSave("a,b\n1,2\n", Formats.FMT_CSV, head, StandardCharsets.UTF_8, "\n");
        assertThat(new String(saved.bytes(), StandardCharsets.UTF_8)).isEqualTo("a,b\r\n1,2\r\n");
    }

    @Test @DisplayName("a CSV saved over UTF-16 preserves cell line breaks as well as encoding and BOM")
    void csvCellLineBreaksInUtf16() {
        byte[] mark = {(byte) 0xFF, (byte) 0xFE};
        byte[] head = withMark(mark, "\"head\nline\"\r\nold\r\n".getBytes(StandardCharsets.UTF_16LE));
        TextEncoder.Encoded saved = TextEncoder.forSave("note\n\"one\ntwo\"\n", Formats.FMT_CSV,
              head, null, "\n");
        assertThat(saved.bytes()).isEqualTo(withMark(mark,
              "note\r\n\"one\ntwo\"\r\n".getBytes(StandardCharsets.UTF_16LE)));
    }

    @Test @DisplayName("a CSV saved over a Windows-1252 file stays Windows-1252, with its CRLF")
    void legacyEncodingKept() {
        byte[] head = "name,city\r\nJosé,Zürich\r\n".getBytes(WINDOWS_1252);
        TextEncoder.Encoded saved = TextEncoder.forSave("name,city\nRené,Köln\n", Formats.FMT_CSV, head, WINDOWS_1252, "\n");
        assertThat(saved.bytes()).isEqualTo("name,city\r\nRené,Köln\r\n".getBytes(WINDOWS_1252));
        assertThat(saved.charset()).isEqualTo(WINDOWS_1252);
    }

    @Test @DisplayName("Excel's CSV UTF-8 keeps the byte-order mark Excel needs")
    void utf8MarkKept() {
        byte[] head = withMark(UTF8_MARK, "a;b\r\n1;2\r\n".getBytes(StandardCharsets.UTF_8));
        TextEncoder.Encoded saved = TextEncoder.forSave("a;b\nÄ;2\n", Formats.FMT_CSV, head, StandardCharsets.UTF_8, "\n");
        assertThat(saved.bytes()).isEqualTo(withMark(UTF8_MARK, "a;b\r\nÄ;2\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test @DisplayName("a UTF-16 file keeps its mark, and its CRLF is read as CRLF")
    void utf16() {
        byte[] mark = {(byte) 0xFF, (byte) 0xFE};
        byte[] head = withMark(mark, "a\tb\r\n1\t2\r\n".getBytes(StandardCharsets.UTF_16LE));
        TextEncoder.Encoded saved = TextEncoder.forSave("a\tb\n3\t4\n", Formats.FMT_CSV, head, null, "\n");
        assertThat(saved.bytes()).isEqualTo(withMark(mark, "a\tb\r\n3\t4\r\n".getBytes(StandardCharsets.UTF_16LE)));
        assertThat(saved.charset()).isEqualTo(StandardCharsets.UTF_16LE);
    }

    @Test @DisplayName("text the file's encoding cannot hold is written as UTF-8, and the refusal named")
    void unencodable() {
        byte[] head = "a,b\n1,2\n".getBytes(StandardCharsets.ISO_8859_1);
        TextEncoder.Encoded saved = TextEncoder.forSave("price\n€5\n", Formats.FMT_CSV, head, StandardCharsets.ISO_8859_1, "\n");
        assertThat(saved.bytes()).isEqualTo("price\n€5\n".getBytes(StandardCharsets.UTF_8));
        assertThat(saved.refused()).isEqualTo(StandardCharsets.ISO_8859_1);
    }

    @Test @DisplayName("JSON, XML, YAML, TOML and Protobuf are UTF-8 without a mark whatever they replace")
    void utf8Formats() {
        byte[] head = withMark(UTF8_MARK, "{\"a\": \"é\"}\r\n".getBytes(StandardCharsets.UTF_8));
        for (String format : new String[]{Formats.FMT_JSON, Formats.FMT_XML, Formats.FMT_YAML, Formats.FMT_TOML,
              Formats.FMT_PROTO, Formats.FMT_SCHEMA}) {
            TextEncoder.Encoded saved = TextEncoder.forSave("x: é\n", format, head, WINDOWS_1252, "\n");
            assertThat(saved.bytes()).as(format).isEqualTo("x: é\r\n".getBytes(StandardCharsets.UTF_8));
            assertThat(saved.refused()).as(format).isNull();
        }
    }

    @Test @DisplayName("Java and Kotlin keep the encoding the IDE reads the replaced file in")
    void sources() {
        byte[] head = "class A {}\n".getBytes(WINDOWS_1252);
        assertThat(TextEncoder.forSave("// é\n", Formats.FMT_JAVA, head, WINDOWS_1252, "\n").bytes())
              .isEqualTo("// é\n".getBytes(WINDOWS_1252));
        assertThat(TextEncoder.forSave("// é\n", Formats.FMT_KOTLIN, head, WINDOWS_1252, "\n").charset())
              .isEqualTo(WINDOWS_1252);
    }
}
