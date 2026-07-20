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
    public static final String FMT_PROTO = "Protobuf";
    public static final String FMT_JAVA  = "Java POJO";

    /**
     * Lenient reader for JSON input: accepts comments, trailing commas,
     * single quotes and unquoted field names (pasted JS object literals).
     * Input is normalised through this mapper into strict JSON before it
     * reaches the downstream converters.
     */
    private static final ObjectMapper LENIENT_JSON = JsonMapper.builder()
          .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
          .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
          .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
          .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
          .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

    private final JsonXmlConverter  jsonXml  = new JsonXmlConverter();
    private final JsonYamlConverter jsonYaml = new JsonYamlConverter();
    private final CsvConverter      csv      = new CsvConverter();
    private final TomlConverter     toml     = new TomlConverter();
    private final ProtoConverter    proto    = new ProtoConverter();
    private final JavaPojoGenerator pojo     = new JavaPojoGenerator();

    /**
     * Normalise input to JSON as the internal pivot format.
     * autoClose is applied once for JSON input to repair truncated brackets.
     */
    public String normalizeToJson(String rawInput, String inFmt, boolean inferTypes)
          throws Exception {
        String input = FMT_JSON.equals(inFmt) ? autoClose(rawInput) : rawInput;
        return switch (inFmt) {
            // Lenient parse (comments, trailing commas, single quotes), then
            // re-serialize so downstream converters always see strict JSON.
            case FMT_JSON  -> LENIENT_JSON.writeValueAsString(LENIENT_JSON.readTree(input));
            case FMT_XML   -> jsonXml.xmlToJson(input, inferTypes);
            case FMT_YAML  -> jsonYaml.yamlToJson(input);
            case FMT_CSV   -> csv.csvToJson(input, inferTypes);
            case FMT_TOML  -> toml.tomlToJson(input);
            case FMT_PROTO -> proto.protoToJson(input);
            default -> throw new UnsupportedOperationException("Unknown input: " + inFmt);
        };
    }

    /** JSON pivot -> desired output format. */
    public String renderFromJson(String asJson, String outFmt, CsvConverter.CsvMode csvMode,
          boolean useLombok, boolean detectDates) throws Exception {
        return switch (outFmt) {
            case FMT_JSON  -> prettyJson(asJson);
            case FMT_XML   -> jsonXml.jsonToXml(asJson);
            case FMT_YAML  -> jsonYaml.jsonToYaml(asJson);
            case FMT_CSV   -> csv.jsonToCsv(asJson, csvMode);
            case FMT_TOML  -> toml.jsonToToml(asJson);
            case FMT_PROTO -> proto.jsonToProto(asJson);
            case FMT_JAVA  -> pojo.fromJson(asJson, useLombok, detectDates);
            default -> throw new UnsupportedOperationException("Unknown output: " + outFmt);
        };
    }

    /** Pretty-prints or canonicalizes input in its own format (the Format action). */
    public String formatInput(String input, String fmt, boolean inferTypes) throws Exception {
        return switch (fmt) {
            case FMT_JSON  -> prettyJson(autoClose(input));
            case FMT_XML   -> prettyXml(input);
            case FMT_YAML  -> jsonYaml.jsonToYaml(jsonYaml.yamlToJson(input));
            case FMT_TOML  -> toml.jsonToToml(toml.tomlToJson(input));
            case FMT_CSV   -> csv.jsonToCsv(csv.csvToJson(input, inferTypes),
                                            CsvConverter.CsvMode.FLAT_FIRST);
            case FMT_PROTO -> input.replaceAll("[ \t]+\n", "\n")
                                   .replaceAll("\n{3,}", "\n\n").trim();
            default        -> input;
        };
    }

    /** Parses the JSON pivot once for callers that need the tree (row estimates). */
    public JsonNode parseJson(String json) throws Exception {
        return LENIENT_JSON.readTree(json);
    }

    public long estimateCsvRows(JsonNode pivot, CsvConverter.CsvMode mode) {
        return csv.estimateRowCount(pivot, mode);
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
