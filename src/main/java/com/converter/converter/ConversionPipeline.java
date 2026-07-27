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

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;

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
              .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES);
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
        // Sorting the pivot rather than each renderer's output means every target
        // format inherits key ordering from one place.
        return opts.sortKeys() ? sortKeys(pivot) : pivot;
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
        boolean inferTypes = opts.inferTypes();
        String formatted = switch (fmt) {
            case FMT_JSON  -> prettyJson(autoClose(input));
            case FMT_XML   -> prettyXml(input);
            case FMT_YAML  -> jsonYaml.jsonToYaml(jsonYaml.yamlToJson(input));
            case FMT_TOML  -> toml.jsonToToml(toml.tomlToJson(input));
            case FMT_CSV   -> csv.jsonToCsv(
                                    parseJson(csv.csvToJson(input, inferTypes, opts.csvFormat())),
                                    CsvConverter.CsvMode.FLAT_FIRST, opts.csvFormat());
            case FMT_PROTO -> input.replaceAll("[ \t]+\n", "\n")
                                   .replaceAll("\n{3,}", "\n\n").trim();
            default        -> input;
        };
        // Key sorting is a JSON-tree operation; only the tree-backed formats can
        // honour it without a lossy round-trip through their own syntax.
        if (opts.sortKeys() && FMT_JSON.equals(fmt)) return prettyJson(sortKeys(formatted));
        return formatted;
    }

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
        String s = text.strip();
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

        // TOML tables ([section]) and key = value. Checked before YAML because
        // 'key = value' is not valid YAML mapping syntax, while 'key: value' is.
        if (TOML_MARKER.matcher(s).find()) return FMT_TOML;

        // A YAML mapping or list at the top level.
        if (YAML_MARKER.matcher(s).find()) return FMT_YAML;

        // CSV last: it is the weakest signal, so require a delimiter in the
        // header line and a consistent column count on the following line.
        if (looksLikeCsv(s)) return FMT_CSV;

        return null;
    }

    private static final java.util.regex.Pattern PROTO_MARKER = java.util.regex.Pattern.compile(
          "(?m)^\\s*(syntax\\s*=|message\\s+\\w+\\s*\\{|enum\\s+\\w+\\s*\\{|package\\s+[\\w.]+\\s*;)");
    private static final java.util.regex.Pattern TOML_MARKER = java.util.regex.Pattern.compile(
          "(?m)^\\s*(\\[[^]]+]\\s*$|[A-Za-z_][\\w.-]*\\s*=)");
    private static final java.util.regex.Pattern YAML_MARKER = java.util.regex.Pattern.compile(
          "(?m)^\\s*(-\\s+\\S|[A-Za-z_][\\w.-]*\\s*:(\\s|$))");

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

    /** Header plus at least one row, both with the same number of commas. */
    private static boolean looksLikeCsv(String s) {
        String[] lines = s.split("\r?\n", 3);
        if (lines.length < 2) return false;
        long headerCommas = lines[0].chars().filter(c -> c == ',').count();
        if (headerCommas == 0) return false;
        return lines[1].chars().filter(c -> c == ',').count() == headerCommas;
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
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escape   = false;
        for (char c : json.toCharArray()) {
            if (escape)        { escape = false; continue; }
            if (c == '\\')     { if (inString) escape = true; continue; }
            if (c == '"')      { inString = !inString; continue; }
            if (inString)      continue;
            if (c == '{')      stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') { if (!stack.isEmpty()) stack.pop(); }
        }
        StringBuilder sb = new StringBuilder(json);
        if (escape)   sb.append('\\');
        if (inString) sb.append('"');
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
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
        doc.getDocumentElement().normalize();
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
