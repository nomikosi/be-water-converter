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

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI-independent conversion pipeline: normalises any supported input format
 * to JSON (the internal pivot), renders JSON to any output format, and
 * provides the per-format input formatting used by the Format action.
 */
public class ConversionPipeline {

    public static final String FMT_JSON  = "JSON";
    public static final String FMT_XML   = "XML";
    public static final String FMT_YAML  = "YAML";
    public static final String FMT_CSV   = "CSV";
    public static final String FMT_TOML  = "TOML";
    public static final String FMT_PROTO  = "Protobuf";
    public static final String FMT_JAVA   = "Java POJO";
    public static final String FMT_SCHEMA = "JSON Schema";
    public static final String FMT_KOTLIN = "Kotlin";

    /**
     * Lenient read settings for JSON input: accepts comments, trailing commas,
     * single quotes and unquoted field names (pasted JS object literals).
     * Input is normalised through these into strict JSON before it reaches the
     * downstream converters.
     */
    private static JsonMapper.Builder lenientReader() {
        return JsonMapper.builder()
              .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
              .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
              .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
              .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
              .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
              // Without this, readTree stops at the first top-level value and
              // silently discards the rest: JSONL kept only its first record and
              // trailing garbage was accepted. Failing is strictly better than
              // Format overwriting the editor with a truncated document.
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              // Floats would otherwise become double: 1e400 turned into the
              // STRING "Infinity", 1e-400 into 0.0, and long decimals lost
              // digits — including inside canonicalJson, which made Compare
              // report differing documents as equal.
              .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
              // Keeping BigDecimal is not enough on its own: the default node
              // factory calls stripTrailingZeros, which rewrote 1.0 as 1 and
              // 100.00 as 1E+2 — and Format wrote that back over the document.
              .nodeFactory(new JsonNodeFactory(true))
              // A repeated key kept only its last value, silently: {"a":1,"a":2}
              // read as {"a":2}, and Format wrote the half-document back. The
              // YAML reader already refuses duplicates; JSON now does the same.
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    /**
     * Strips a leading UTF-8 BOM. Excel writes one on "CSV UTF-8" export, and
     * nothing downstream treats U+FEFF as whitespace: it silently became part of
     * the first CSV header name, and made JSON, TOML and XML fail to parse.
     */
    public static String stripBom(String text) {
        // The escape, not a literal U+FEFF char: the character is invisible, so
        // a literal survives review poorly and dies silently if the file is
        // ever re-encoded.
        return text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF'
              ? text.substring(1) : text;
    }

    /** Indenting mapper — for JSON the user actually sees. */
    private static final ObjectMapper LENIENT_JSON =
          lenientReader().enable(SerializationFeature.INDENT_OUTPUT).build();

    /**
     * Compact mapper for the internal JSON pivot. The pivot is re-parsed by the
     * next stage and never displayed, so indenting it only inflates the string:
     * on a 20k-row array the indented pivot measured ~1.5x the compact one.
     */
    private static final ObjectMapper COMPACT_JSON = lenientReader().build();

    private final JsonXmlConverter  jsonXml  = new JsonXmlConverter();
    private final JsonYamlConverter jsonYaml = new JsonYamlConverter();
    private final CsvConverter      csv      = new CsvConverter();
    private final TomlConverter     toml     = new TomlConverter();
    private final ProtoConverter    proto    = new ProtoConverter();
    private final JavaPojoGenerator pojo     = new JavaPojoGenerator();
    private final JsonSchemaGenerator schema = new JsonSchemaGenerator();
    private final KotlinDataClassGenerator kotlin = new KotlinDataClassGenerator();

    /**
     * Normalise input to JSON as the internal pivot format.
     * autoClose is applied once for JSON input to repair truncated brackets.
     */
    public String normalizeToJson(String rawInput, String inFmt, boolean inferTypes)
          throws Exception {
        return normalizeToJson(rawInput, inFmt, ConversionOptions.DEFAULTS.withInferTypes(inferTypes));
    }

    public String normalizeToJson(String rawInput, String inFmt, ConversionOptions opts)
          throws Exception {
        rawInput = stripBom(rawInput);
        String input = FMT_JSON.equals(inFmt) ? autoClose(rawInput) : rawInput;
        boolean inferTypes = opts.inferTypes();
        String pivot = switch (inFmt) {
            // Lenient parse (comments, trailing commas, single quotes), then
            // re-serialize compactly so downstream converters always see strict
            // JSON without paying to indent a string nobody reads.
            case FMT_JSON  -> COMPACT_JSON.writeValueAsString(COMPACT_JSON.readTree(input));
            case FMT_XML   -> jsonXml.xmlToJson(input, inferTypes);
            case FMT_YAML  -> jsonYaml.yamlToJson(input);
            case FMT_CSV   -> csv.csvToJson(input, inferTypes, opts.csvFormat());
            case FMT_TOML  -> toml.tomlToJson(input);
            case FMT_PROTO -> proto.protoToJson(input);
            default -> throw new UnsupportedOperationException("Unknown input: " + inFmt);
        };
        // One parse and one serialise however many options are on: applying them
        // to strings cost a full round trip each, which measured x2.3 on a 2.7 MB
        // document with both enabled.
        if (opts.hasFilter() || opts.sortKeys()) {
            JsonNode tree = COMPACT_JSON.readTree(pivot);
            // Filter first: narrowing means the sort only walks what will render.
            if (opts.hasFilter()) tree = JsonPathFilter.apply(tree, opts.filterPath());
            // Sorting the pivot rather than each renderer's output means every
            // target format inherits key ordering from one place.
            if (opts.sortKeys()) tree = sortNode(tree);
            pivot = COMPACT_JSON.writeValueAsString(tree);
        }
        return pivot;
    }

    /**
     * Normalises any supported format to sorted, pretty-printed JSON — the form
     * used to compare two documents that carry the same data in different
     * formats or different key orders.
     */
    public String canonicalJson(String input, String fmt) throws Exception {
        return canonicalJson(input, fmt, ConversionOptions.DEFAULTS);
    }

    /**
     * @param opts the user's own settings. Passing defaults here made Compare
     *             read a semicolon- or tab-delimited CSV as a single column named
     *             after the whole header line, so a byte-perfect conversion was
     *             reported as a wholesale difference.
     */
    public String canonicalJson(String input, String fmt, ConversionOptions opts) throws Exception {
        // One parse, one in-memory sort, one serialise — chaining
        // prettyJson(sortKeys(...)) instead cost three full round trips, which
        // measured over a second per Compare click on a 10 MB document.
        JsonNode tree = COMPACT_JSON.readTree(normalizeToJson(input, fmt, opts));
        return LENIENT_JSON.writeValueAsString(sortNode(tree));
    }

    /** JSON pivot -> desired output format. */
    public String renderFromJson(String asJson, String outFmt, CsvConverter.CsvMode csvMode,
          boolean useLombok, boolean detectDates) throws Exception {
        return renderFromJson(asJson, outFmt, ConversionOptions.DEFAULTS
              .withCsvMode(csvMode).withLombok(useLombok).withDetectDates(detectDates));
    }

    public String renderFromJson(String asJson, String outFmt, ConversionOptions opts)
          throws Exception {
        return switch (outFmt) {
            case FMT_JSON   -> prettyJson(asJson);
            case FMT_XML    -> jsonXml.jsonToXml(asJson);
            case FMT_YAML   -> jsonYaml.jsonToYaml(asJson);
            case FMT_CSV    -> csv.jsonToCsv(parseJson(asJson), opts.csvMode(), opts.csvFormat());
            case FMT_TOML   -> toml.jsonToToml(asJson);
            case FMT_PROTO  -> proto.jsonToProto(asJson);
            case FMT_JAVA   -> pojo.fromJson(asJson, opts.useLombok(), opts.detectDates());
            case FMT_SCHEMA -> schema.fromJson(asJson);
            case FMT_KOTLIN -> kotlin.fromJson(asJson, opts.detectDates());
            default -> throw new UnsupportedOperationException("Unknown output: " + outFmt);
        };
    }

    /**
     * Recursively sorts object keys alphabetically, leaving array order intact.
     * Makes output diffable across runs and across sources that emit the same
     * data in different key orders.
     */
    public String sortKeys(String json) throws Exception {
        return COMPACT_JSON.writeValueAsString(sortNode(COMPACT_JSON.readTree(json)));
    }

    private JsonNode sortNode(JsonNode node) {
        if (node.isObject()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            com.fasterxml.jackson.databind.node.ObjectNode out = COMPACT_JSON.createObjectNode();
            for (String name : names) out.set(name, sortNode(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = COMPACT_JSON.createArrayNode();
            for (JsonNode item : node) out.add(sortNode(item));
            return out;
        }
        return node;
    }

    /** Pretty-prints or canonicalizes input in its own format (the Format action). */
    public String formatInput(String input, String fmt, boolean inferTypes) throws Exception {
        return formatInput(input, fmt, ConversionOptions.DEFAULTS.withInferTypes(inferTypes));
    }

    public String formatInput(String input, String fmt, ConversionOptions opts) throws Exception {
        input = stripBom(input);
        String formatted = switch (fmt) {
            case FMT_JSON  -> prettyJson(autoClose(input));
            case FMT_XML   -> prettyXml(input);
            // Per document, not once over the whole stream: yamlToJson turns a
            // multi-document file into a JSON array, and rendering that back
            // gave one YAML sequence — Format replaced a two-manifest k8s file
            // with a single list and wrote it over the editor.
            case FMT_YAML  -> jsonYaml.formatPreservingDocuments(input, opts.sortKeys());
            case FMT_TOML  -> formatToml(input, opts.sortKeys());
            // Positional, never through the pivot: Format only re-lays-out the
            // document. Going through objects keyed by header renamed headers,
            // dropped the ones a ragged row lacked, and inferring types rewrote
            // 1.50 as 1.5 and erased a literal "null" cell.
            case FMT_CSV   -> csv.reformat(input, opts.csvFormat());
            case FMT_PROTO -> input.replaceAll("[ \t]+\n", "\n")
                                   .replaceAll("\n{3,}", "\n\n").trim();
            default        -> input;
        };
        // YAML and TOML sort inside their own formatters above, because both
        // already pass through the JSON tree there and the sort has to happen
        // while the tree exists.
        if (opts.sortKeys() && FMT_JSON.equals(fmt)) return prettyJson(sortKeys(formatted));
        return formatted;
    }

    /**
     * Re-lays-out TOML, refusing rather than retyping a date.
     *
     * <p>TOML has first-class dates and JSON does not, so the pivot turns
     * {@code created = 1979-05-27T07:32:00Z} into a string and rendering it back
     * writes {@code created = "1979-05-27T07:32:00Z"} — Format silently changed
     * a date into text, in the user's own file.
     */
    private String formatToml(String input, boolean sortKeys) throws Exception {
        String scannable = TomlConverter.maskStringsAndComments(input);
        Matcher dated = TOML_DATE.matcher(scannable);
        if (dated.find())
            throw new IllegalArgumentException(
                  "Format would rewrite the date " + dated.group().trim() + " as a quoted string: "
                  + "TOML has date and time types and the JSON step this uses does not. "
                  + "The document is left as it is.");
        // The same refusal for the number forms JSON cannot spell: 0xFF came
        // back as 255, 1_000 as 1000, and inf as the STRING 'Infinity'.
        Matcher literal = TOML_NON_DECIMAL.matcher(scannable);
        if (literal.find())
            throw new IllegalArgumentException(
                  "Format would rewrite " + literal.group(1) + ": hexadecimal, octal, binary and "
                  + "underscore-separated numbers come back as plain decimals, and inf and nan "
                  + "as text, because the JSON step this uses has no other way to write them. "
                  + "The document is left as it is.");
        String pivot = toml.tomlToJson(input);
        // jsonToToml renders an empty table as the literal "# empty document",
        // which as a FORMAT replaced the user's own comments with that sentence.
        // There is no layout to apply to a document with no values anyway.
        if (parseJson(pivot).isEmpty()) return input;
        return toml.jsonToToml(sortKeys ? sortKeys(pivot) : pivot);
    }

    /**
     * A bare TOML date, datetime or time in value position.
     *
     * <p>Anchored on what comes BEFORE rather than on {@code =}: a date is just
     * as likely to be an array element or an inline-table value, and requiring
     * {@code =} meant only the first element of {@code d = [1979-05-27, …]} could
     * ever match, so the rest were still retyped.
     */
    private static final Pattern TOML_DATE = Pattern.compile(
          "(?<=[=\\[,{]|\\A)\\s*(\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}:\\d{2}\\S*)?"
          + "|\\d{2}:\\d{2}:\\d{2}\\S*)\\s*(?=$|[,}\\]#\\r\\n])",
          Pattern.MULTILINE);

    /**
     * A TOML number in value position that JSON cannot carry as written: a
     * hex, octal or binary literal, one with underscore separators (the
     * lookahead requires an underscore somewhere in the run), or inf/nan.
     * Anchored on what comes before, like {@link #TOML_DATE}, so a bare key
     * such as {@code my_key} is never mistaken for a value.
     */
    private static final Pattern TOML_NON_DECIMAL = Pattern.compile(
          "(?<=[=\\[,{])\\s*([+-]?0[xob][0-9A-Fa-f_]+"
          + "|[+-]?(?=[0-9.eE+\\-]*_)[0-9][0-9_.eE+\\-]*"
          + "|[+-]?(?:inf|nan))\\s*(?=$|[,}\\]#\\r\\n])",
          Pattern.MULTILINE);

    /** Parses the JSON pivot once for callers that need the tree (row estimates). */
    public JsonNode parseJson(String json) throws Exception {
        return LENIENT_JSON.readTree(json);
    }

    /**
     * Guesses the input format from the content itself, for text that arrives
     * without a filename (paste, or a file with no useful extension). Returns
     * null when nothing matches confidently — the caller keeps its current
     * selection rather than guessing wrong.
     */
    public static String detectFormat(String text) {
        if (text == null) return null;
        String s = stripBom(text).strip();
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

        // Nothing decisive on line one: fall back to the document-wide scan.
        if (TOML_MARKER.matcher(s).find()) return FMT_TOML;
        if (YAML_MARKER.matcher(s).find()) return FMT_YAML;

        // CSV last: it is the weakest signal, so require a delimiter in the
        // header line and a consistent column count on the following line.
        if (detectCsvDelimiter(s) != null) return FMT_CSV;

        return null;
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
        String s = stripBom(text).strip();
        for (char delimiter : CSV_DELIMITERS)
            if (looksLikeCsv(s, delimiter)) return delimiter;
        return null;
    }

    /**
     * {@code package x;} is deliberately NOT here. It is legal proto, but it is
     * also an ordinary line of Java, Kotlin or Go, and scanning the whole
     * document for it classified any YAML that merely embedded such a line — a
     * k8s ConfigMap carrying a source file — as Protobuf. The remaining markers
     * are ones nothing else writes at the start of a line.
     */
    private static final java.util.regex.Pattern PROTO_MARKER = java.util.regex.Pattern.compile(
          "(?m)^\\s*(syntax\\s*=\\s*[\"']proto[23][\"']|message\\s+\\w+\\s*\\{|enum\\s+\\w+\\s*\\{)");
    private static final java.util.regex.Pattern TOML_MARKER = java.util.regex.Pattern.compile(
          "(?m)^\\s*(\\[[^]]+]\\s*$|[A-Za-z_][\\w.-]*\\s*=)");
    private static final java.util.regex.Pattern YAML_MARKER = java.util.regex.Pattern.compile(
          "(?m)^\\s*(-\\s+\\S|[A-Za-z_][\\w.-]*\\s*:(\\s|$))");

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

    /** A lone [table] or [[array.of.tables]] header on line 1, plus a later 'key =' line. */
    private static final java.util.regex.Pattern TOML_TABLE_HEADER =
          java.util.regex.Pattern.compile("^\\[\\[?[^\\[\\]]+]]?$");
    private static final java.util.regex.Pattern TOML_KEY_VALUE =
          java.util.regex.Pattern.compile("(?m)^\\s*[A-Za-z_\"'][\\w.\\-\"']*\\s*=");

    private static boolean looksLikeTomlTable(String s) {
        int newline = s.indexOf('\n');
        if (newline < 0) return false;                       // single line: a JSON array
        String firstLine = s.substring(0, newline).stripTrailing();
        return TOML_TABLE_HEADER.matcher(firstLine).matches()
              && TOML_KEY_VALUE.matcher(s.substring(newline)).find();
    }

    /**
     * Header plus at least one row with a matching column count. Delimiters
     * inside quoted fields do not count, a third line must agree when present,
     * and sentence-like first lines are rejected — two lines of prose that
     * happen to contain one comma each were otherwise detected as CSV.
     */
    private static boolean looksLikeCsv(String s, char delimiter) {
        String[] lines = s.split("\r?\n", 4);
        if (lines.length < 2 || lines[1].isBlank()) return false;

        String header = lines[0];
        // ". " or a trailing period is prose punctuation, not a column name.
        if (header.contains(". ") || header.stripTrailing().endsWith(".")) return false;

        int expected = countDelimitersOutsideQuotes(header, delimiter);
        if (expected == 0) return false;
        if (countDelimitersOutsideQuotes(lines[1], delimiter) != expected) return false;
        if (lines.length > 2 && !lines[2].isBlank()
              && countDelimitersOutsideQuotes(lines[2], delimiter) != expected) return false;
        return true;
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

    public long estimateCsvRows(JsonNode pivot, CsvConverter.CsvMode mode) {
        return csv.estimateRowCount(pivot, mode);
    }

    public String renderCsv(JsonNode pivot, CsvConverter.CsvMode mode,
          CsvConverter.CsvFormat format) throws Exception {
        return csv.jsonToCsv(pivot, mode, format);
    }

    public String renderCsv(JsonNode pivot, CsvConverter.CsvMode mode) throws Exception {
        return csv.jsonToCsv(pivot, mode);
    }

    /**
     * Leniently repairs truncated JSON: closes a dangling escape, an
     * unterminated string, and any unclosed {@code {} / []} brackets.
     * Only applied when the input format is JSON.
     */
    public String autoClose(String json) {
        Scan scan = scan(json);
        // An unterminated comment needs no closer, and appending one inside it
        // would be appending to a comment.
        if (scan.unterminatedBlockComment()) return json;
        return json + scan.closers();
    }

    /** What one pass over JSON text finds: the closers it lacks, and the comments it carries. */
    private record Scan(String closers, boolean unterminatedBlockComment, int comments) {}

    private static Scan scan(String json) {
        Deque<Character> stack = new ArrayDeque<>();
        char quote       = 0;          // 0 = not in a string, else the opening quote
        boolean escape   = false;
        // The reader accepts comments and single quotes, so the scan has to know
        // about them too: a brace inside // a note, or inside 'it {', was counted
        // as real and this appended a closer that made valid input fail to parse.
        boolean lineComment = false, blockComment = false;
        int comments = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
            if (lineComment)  { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (escape)       { escape = false; continue; }
            if (quote != 0) {
                if (c == '\\')      escape = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'')            { quote = c; continue; }
            if (c == '/' && next == '/')          { lineComment = true; comments++; i++; continue; }
            if (c == '#')                         { lineComment = true; comments++; continue; }
            if (c == '/' && next == '*')          { blockComment = true; comments++; i++; continue; }
            if (c == '{')                          stack.push('}');
            else if (c == '[')                     stack.push(']');
            else if (c == '}' || c == ']')       { if (!stack.isEmpty()) stack.pop(); }
        }
        StringBuilder closers = new StringBuilder();
        if (escape)     closers.append('\\');
        if (quote != 0) closers.append(quote);
        while (!stack.isEmpty()) closers.append(stack.pop());
        return new Scan(closers.toString(), blockComment, comments);
    }

    /**
     * What Format would silently discard from a document, as a phrase ("2
     * comments and 1 anchor"), or null when nothing would be lost.
     *
     * <p>Comments survive XML (the DOM keeps them) and Protobuf (whitespace-only
     * tidying). The formats that pass through the JSON tree keep only the data,
     * and the UI asks before it overwrites the editor with less than it held.
     */
    public String formatLosses(String input, String fmt) {
        input = stripBom(input);
        return switch (fmt) {
            case FMT_YAML -> jsonYaml.countFormatLosses(input).describe();
            // A comment-only TOML file is returned untouched by formatToml, so
            // its comments are not at risk.
            case FMT_TOML -> TomlConverter.maskStringsAndComments(input).isBlank() ? null
                  : new FormatLosses(TomlConverter.countComments(input), 0).describe();
            case FMT_JSON -> new FormatLosses(scan(input).comments(), 0).describe();
            default -> null;
        };
    }

    /** Counts of what a Format would drop. */
    public record FormatLosses(int comments, int anchors) {
        /** "2 comments and 1 anchor", or null when there is nothing to report. */
        public String describe() {
            String c = comments == 0 ? null : comments + (comments == 1 ? " comment" : " comments");
            String a = anchors == 0 ? null : anchors + (anchors == 1 ? " anchor" : " anchors");
            if (c == null) return a;
            return a == null ? c : c + " and " + a;
        }
    }

    public String prettyJson(String json) throws Exception {
        return LENIENT_JSON.writeValueAsString(LENIENT_JSON.readTree(json));
    }

    /**
     * Pretty-prints XML via DOM + Transformer so the original root element,
     * attributes and structure are preserved (Jackson's tree model drops the
     * root element name). External entities and DTDs are disabled.
     */
    public String prettyXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf =
              javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setExpandEntityReferences(false);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder()
              .parse(new org.xml.sax.InputSource(new StringReader(xml)));
        // Without this the serializer appends standalone="no" to a declaration
        // the document wrote without it.
        doc.setXmlStandalone(true);
        doc.getDocumentElement().normalize();
        rejectMixedContent(doc.getDocumentElement());
        stripWhitespaceNodes(doc.getDocumentElement());

        javax.xml.transform.TransformerFactory tf =
              javax.xml.transform.TransformerFactory.newInstance();
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        javax.xml.transform.Transformer t = tf.newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION,
              xml.stripLeading().startsWith("<?xml") ? "no" : "yes");

        StringWriter out = new StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(doc),
              new javax.xml.transform.stream.StreamResult(out));
        return out.toString();
    }

    /**
     * Refuses an element that holds text alongside child elements.
     *
     * <p>The JDK serializer indents every child, text included, so
     * {@code <p>Hello <b>big</b> world</p>} came back with line breaks and
     * indentation inside its own text — a change to the content, not the
     * layout — and it cannot be told to indent element-only content alone.
     * Comments and processing instructions count as children here because
     * they are indented the same way.
     */
    private static void rejectMixedContent(org.w3c.dom.Element element) {
        org.w3c.dom.NodeList children = element.getChildNodes();
        boolean text = false, markup = false;
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            switch (child.getNodeType()) {
                case org.w3c.dom.Node.TEXT_NODE, org.w3c.dom.Node.CDATA_SECTION_NODE ->
                      text |= !child.getTextContent().isBlank();
                case org.w3c.dom.Node.ELEMENT_NODE, org.w3c.dom.Node.COMMENT_NODE,
                     org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE -> markup = true;
                default -> { }
            }
        }
        if (text && markup)
            throw new IllegalArgumentException(
                  "Format would insert line breaks into the text of <" + element.getTagName()
                  + ">, which mixes text with child elements. Indenting that changes the "
                  + "content rather than the layout, so the document is left as it is.");
        for (int i = 0; i < children.getLength(); i++)
            if (children.item(i) instanceof org.w3c.dom.Element child) rejectMixedContent(child);
    }

    /** Removes whitespace-only text nodes so re-indenting doesn't stack blank lines. */
    private void stripWhitespaceNodes(org.w3c.dom.Node node) {
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                  && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                stripWhitespaceNodes(child);
            }
        }
    }
}
