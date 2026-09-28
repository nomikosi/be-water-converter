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

import com.converter.core.CsvConverter;
import com.converter.core.FormatDetector;
import com.converter.core.Formats;

import java.util.Locale;
import java.util.Set;

/**
 * What the editor and Project-view actions decide, from plain values: whether
 * to offer themselves, which format a document is in, and which delimiter a CSV
 * uses. Kept free of platform types so the plain unit tests cover it; the
 * actions only gather the values.
 */
final class ContextDecisions {

    private ContextDecisions() {}

    /** Characters sniffed when a menu is built: the menu must open at once. */
    static final int MENU_SNIFF_CHARS = 4_096;

    /** Characters sniffed to choose a format once an action runs, as a paste is. */
    static final int ACTION_SNIFF_CHARS = 64 * 1024;

    /** What the IDE takes a file to be. */
    enum Kind {
        /** One of the formats converted here, whatever the extension says: JSON, XML, YAML… */
        DATA,
        /** Plain text, an unknown type, or no file at all: only the content can tell. */
        PLAIN,
        /** Any other language — Java, Python, Markdown, a shell script. */
        OTHER
    }

    /**
     * Words in the names of the IDE file types that hold the formats converted
     * here. SVG and XHTML are XML under file types of their own, and lost the
     * actions when the content stopped being sniffed for every file.
     */
    private static final Set<String> DATA_TYPE_WORDS =
          Set.of("json", "xml", "yaml", "toml", "csv", "tsv", "proto", "svg", "xhtml");

    /**
     * The kind of an IDE file type, by its name.
     *
     * @param plainOrUnknown whether the IDE has no language for the file
     */
    static Kind kindOf(String fileTypeName, boolean plainOrUnknown) {
        if (plainOrUnknown || fileTypeName == null) return Kind.PLAIN;
        String name = fileTypeName.toLowerCase(Locale.ROOT);
        for (String word : DATA_TYPE_WORDS) if (name.contains(word)) return Kind.DATA;
        return Kind.OTHER;
    }

    /**
     * Whether the actions offer themselves.
     *
     * <p>Sniffing alone put them on almost every file: a Java class, a Python
     * or shell script, a README and gradle.properties all hold a line that
     * looks like TOML's {@code key = value} or YAML's {@code key: value}, and
     * converting any of them could only fail. A file in another language is
     * therefore offered only for a selection that reads as a format.
     *
     * @param selection the selected text, at most {@link #MENU_SNIFF_CHARS} of
     *                  it, or null when nothing is selected
     * @param prefix    the start of the document, likewise bounded, or null
     */
    static boolean offer(String fileName, Kind kind, CharSequence selection, CharSequence prefix) {
        if (fileName != null && Formats.inputForFileName(fileName) != null) return true;
        if (selection != null && !selection.toString().isBlank())
            return FormatDetector.detectFormat(selection.toString()) != null;
        if (kind == Kind.OTHER) return false;
        if (kind == Kind.DATA) return true;
        return prefix != null && FormatDetector.detectFormat(prefix.toString()) != null;
    }

    /**
     * The format of a document: by its extension when that says, otherwise by
     * its start. The whole text was sniffed before, which on a large selection
     * took seconds on the EDT.
     */
    static String formatFor(String fileName, String text) {
        String byExtension = Formats.inputForFileName(fileName);
        if (byExtension != null) return byExtension;
        return FormatDetector.detectFormat(text.length() > ACTION_SNIFF_CHARS
              ? text.substring(0, ACTION_SNIFF_CHARS) : text);
    }

    /**
     * The delimiter a CSV text uses: detected from its start, else a tab for a
     * {@code .tsv} file, else null for the remembered choice.
     */
    static Character delimiterFor(String fileName, String text) {
        String start = text.length() > ACTION_SNIFF_CHARS ? text.substring(0, ACTION_SNIFF_CHARS) : text;
        Character detected = FormatDetector.detectCsvDelimiter(start);
        if (detected != null) return detected;
        return fileName != null && fileName.toLowerCase(Locale.ROOT).contains(".tsv") ? '\t' : null;
    }

    /** The CSV format a context-menu conversion reads with: the document's own delimiter, else the remembered one. */
    static CsvConverter.CsvFormat csvFormatFor(String fileName, String text, CsvConverter.CsvFormat remembered) {
        Character delimiter = delimiterFor(fileName, text);
        return delimiter == null ? remembered : CsvConverter.CsvFormat.forDelimiter(delimiter);
    }
}
