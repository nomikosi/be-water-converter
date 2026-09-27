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

import static com.converter.core.Formats.FMT_CSV;
import static com.converter.core.Formats.FMT_JSON;
import static com.converter.core.Formats.FMT_PROTO;
import static com.converter.core.Formats.FMT_TOML;
import static com.converter.core.Formats.FMT_XML;
import static com.converter.core.Formats.FMT_YAML;

import java.util.regex.Pattern;

/**
 * Tells a document's format, and a CSV document's delimiter, from its content
 * alone: for text that arrives without a file name to go by.
 */
public final class FormatDetector {

    private FormatDetector() {}

    /**
     * Guesses the input format from the content itself, for text that arrives
     * without a filename (paste, or a file with no useful extension). Returns
     * null when nothing matches confidently — the caller keeps its current
     * selection rather than guessing wrong.
     */
    public static String detectFormat(String text) {
        if (text == null) return null;
        String raw = TextDecoder.stripBom(text).strip();
        if (raw.isEmpty()) return null;
        // Comments carry no format. Decided on the raw first character, a
        // "// note" or "# note" above a JSON object detected nothing, and a
        // "# note" above "[1, 2]" made the TOML table-header check see the
        // bracket line first and call the array TOML.
        String body = withoutLeadingComments(raw);
        String structural = structuralFormat(body);
        if (structural != null) return structural;

        // CSV last: it is the weakest signal, so require a delimiter in the
        // header line and a consistent column count on the following line.
        //
        // On the document as WRITTEN, unlike every check above it. '#' opens a
        // comment in YAML and TOML but is an ordinary character in a CSV
        // header, and the convention is common in tab-separated exports:
        // stripping "#id,name" left the single line "1,Ann", which is too
        // little to compare column counts against, so a two-line file stopped
        // being recognised at all. This arm therefore sees exactly what it saw
        // before leading comments were skipped for the others.
        if (detectCsvDelimiter(raw) != null) return FMT_CSV;

        // Only then a key anywhere in the document. Before CSV, a mail export
        // whose rows held "Re: invoice" was taken for YAML by that line alone,
        // and the conversion failed on the first comma.
        return documentWideFormat(body);
    }

    /**
     * Every format but CSV, decided from the document with its leading comments
     * removed. Null when nothing matches, which is what hands the question to
     * the CSV check.
     */
    private static String structuralFormat(String s) {
        if (s.isEmpty()) return null;

        // Structural markers first: these are unambiguous.
        char first = s.charAt(0);
        if (first == '{') return FMT_JSON;
        if (first == '<') return FMT_XML;
        if (s.startsWith("---")) return FMT_YAML;
        // '[' is genuinely ambiguous: a JSON array and a TOML [table] header
        // open the same way. Only a following 'key =' line settles it.
        if (first == '[') return looksLikeTomlTable(s) ? FMT_TOML : FMT_JSON;

        // Proto needs a keyword: 'syntax = "proto3";' or a message/enum block.
        if (PROTO_MARKER.matcher(s).find()) return FMT_PROTO;

        // TOML vs YAML is decided from the FIRST significant line, not a
        // document-wide search. Searching the whole document meant one indented
        // KEY=value anywhere — a shell assignment inside a `run: |` block —
        // classified an entire GitHub Actions or k8s file as TOML.
        String firstLine = firstSignificantLine(s);
        if (firstLine != null) {
            if (YAML_MARKER.matcher(firstLine).find()) return FMT_YAML;
            if (TOML_MARKER.matcher(firstLine).find()) return FMT_TOML;
        }

        return null;
    }

    /** Nothing decisive on the first line and not CSV: a key anywhere in the document. */
    private static String documentWideFormat(String s) {
        if (s.isEmpty()) return null;
        if (TOML_MARKER.matcher(s).find()) return FMT_TOML;
        if (YAML_MARKER.matcher(s).find()) return FMT_YAML;
        return null;
    }

    /**
     * The text from its first line that is neither blank nor a {@code #},
     * {@code //} or {@code /* ... *&#47;} comment. Only whole comment LINES are
     * skipped: a comment is what every supported format lets a document open
     * with, and nothing about its content says which format follows.
     */
    static String withoutLeadingComments(String s) {
        int pos = 0;
        while (pos < s.length()) {
            int lineEnd = s.indexOf('\n', pos);
            if (lineEnd < 0) lineEnd = s.length();
            String line = s.substring(pos, lineEnd).strip();
            if (line.startsWith("/*")) {
                int close = s.indexOf("*/", pos + 2);
                if (close < 0) return "";                 // never closed: nothing follows it
                pos = close + 2;
            } else if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                pos = lineEnd + 1;
            } else {
                break;
            }
        }
        return pos >= s.length() ? "" : s.substring(pos).strip();
    }

    /** Delimiters tried in order; comma first, since it is also the default option. */
    private static final char[] CSV_DELIMITERS = {',', ';', '\t'};

    /**
     * The delimiter a CSV-looking text uses, or null when it does not look like
     * CSV at all. Only the comma was tried before, so a semicolon-separated
     * file — the norm across much of Europe — was not recognised as CSV, and a
     * paste of one left whatever format was selected in place.
     */
    public static Character detectCsvDelimiter(String text) {
        if (text == null) return null;
        String s = TextDecoder.stripBom(text).strip();
        // As written first: '#' opens a comment in YAML and TOML but is an
        // ordinary character in a CSV header, and "#id,name" above the rows is
        // the convention tab-separated exports use.
        Character asWritten = delimiterOf(s);
        if (asWritten != null) return asWritten;
        // Then without leading comment lines, for the other shape: a genuine
        // note above a real header. Reading only one of the two forms loses the
        // other, and both are ordinary CSV files.
        String body = withoutLeadingComments(s);
        return body.equals(s) ? null : delimiterOf(body);
    }

    /**
     * The delimiter that splits the header and the rows below it into the most
     * columns, consistently. The first that fitted used to win, and comma is
     * tried first: a tab-separated file whose values each held one comma
     * ("Austin, TX") fitted comma too, and was read as two wide columns.
     * Equal counts keep the order: comma, then semicolon, then tab.
     */
    private static Character delimiterOf(String s) {
        Character best = null;
        int bestColumns = 0;
        for (char delimiter : CSV_DELIMITERS) {
            int columns = csvColumns(s, delimiter);
            if (columns > bestColumns) {
                best = delimiter;
                bestColumns = columns;
            }
        }
        return best;
    }

    /**
     * {@code package x;} is deliberately NOT here. It is legal proto, but it is
     * also an ordinary line of Java, Kotlin or Go, and scanning the whole
     * document for it classified any YAML that merely embedded such a line — a
     * k8s ConfigMap carrying a source file — as Protobuf. The remaining markers
     * are ones nothing else writes at the start of a line.
     *
     * <p>At the very start: a .proto file declares its syntax and top-level
     * types at column 0, while a proto embedded in YAML — a ConfigMap entry, a
     * workflow step writing one out — is indented under its key. Matched
     * indented, the whole YAML file was taken for Protobuf and converted to the
     * one message it carried.
     */
    //
    // Leading whitespace is horizontal and possessive on purpose. As "^\s*" the
    // markers ran over every blank line that followed a line start, failed,
    // and backtracked through the run one character at a time — quadratic in
    // the number of blank lines, and 16,000 of them took seven seconds on the
    // EDT. "[ \t]*+" stops at the line's own end and never backtracks.
    private static final Pattern PROTO_MARKER = Pattern.compile(
          "(?m)^(syntax\\s*+=\\s*+[\"']proto[23][\"']|edition\\s*+=\\s*+[\"']\\d{4}[\"']\\s*+;"
          + "|message\\s++\\w+\\s*+\\{|enum\\s++\\w+\\s*+\\{)");
    private static final Pattern TOML_MARKER = Pattern.compile(
          "(?m)^[ \\t]*+(\\[[^]]+][ \\t]*+(?:#.*)?$|[A-Za-z_][\\w.-]*+[ \\t]*+=)");
    private static final Pattern YAML_MARKER = Pattern.compile(
          "(?m)^[ \\t]*+(-\\s++\\S|[A-Za-z_][\\w.-]*+[ \\t]*+:(\\s|$))");

    /**
     * First line that is neither blank nor a comment. {@code #} introduces a
     * comment in both YAML and TOML, so a leading comment block must not decide
     * the format.
     */
    private static String firstSignificantLine(String s) {
        for (String line : s.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            return line;
        }
        return null;
    }

    /**
     * A lone [table] or [[array.of.tables]] header on line 1, plus a later
     * 'key =' line. The header may carry a comment: "[package] # the crate"
     * was taken for a JSON array.
     */
    private static final java.util.regex.Pattern TOML_TABLE_HEADER =
          java.util.regex.Pattern.compile("^\\[\\[?[^\\[\\]]+]]?[ \\t]*(?:#.*)?$");
    private static final Pattern TOML_KEY_VALUE =
          Pattern.compile("(?m)^[ \\t]*+[A-Za-z_\"'][\\w.\\-\"']*+[ \\t]*+=");

    private static boolean looksLikeTomlTable(String s) {
        int newline = s.indexOf('\n');
        if (newline < 0) return false;                       // single line: a JSON array
        String firstLine = s.substring(0, newline).stripTrailing();
        return TOML_TABLE_HEADER.matcher(firstLine).matches()
              && TOML_KEY_VALUE.matcher(s.substring(newline)).find();
    }

    /**
     * Header plus at least one row with a matching column count. Delimiters
     * inside quoted fields do not count, a third row must agree when present,
     * and sentence-like first lines are rejected — two lines of prose that
     * happen to contain one comma each were otherwise detected as CSV.
     *
     * <p>Rows, not lines: a quoted cell may hold a line break, which Excel
     * writes for any cell with one. Counted per line, "1;Widget;\"Line one" and
     * "Line two\";5" each disagreed with the header, nothing was detected, and
     * the semicolon file was read with the remembered comma.
     */
    private static int csvColumns(String s, char delimiter) {
        java.util.List<String> rows = firstRows(s, 3);
        if (rows.size() < 2 || rows.get(1).isBlank()) return 0;

        String header = rows.get(0);
        // ". " or a trailing period is prose punctuation, not a column name.
        if (header.contains(". ") || header.stripTrailing().endsWith(".")) return 0;

        int expected = countDelimitersOutsideQuotes(header, delimiter);
        if (expected == 0) return 0;
        if (countDelimitersOutsideQuotes(rows.get(1), delimiter) != expected) return 0;
        if (rows.size() > 2 && !rows.get(2).isBlank()
              && countDelimitersOutsideQuotes(rows.get(2), delimiter) != expected) return 0;
        return expected + 1;
    }

    /**
     * Up to {@code max} rows from the start of a CSV text, split at line
     * breaks outside quotes; CR, LF and CRLF all end a row.
     */
    static java.util.List<String> firstRows(String s, int max) {
        java.util.List<String> rows = new java.util.ArrayList<>(max);
        boolean inQuotes = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if ((c == '\n' || c == '\r') && !inQuotes) {
                rows.add(s.substring(start, i));
                if (rows.size() == max) return rows;
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
        }
        rows.add(s.substring(start));
        return rows;
    }

    private static int countDelimitersOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == delimiter && !inQuotes) count++;
        }
        return count;
    }
}
