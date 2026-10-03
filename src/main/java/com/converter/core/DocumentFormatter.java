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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The Format action: re-lays-out a document in its own format, and refuses
 * where the only way to do that would change what the document says.
 */
final class DocumentFormatter {

    private final JsonYamlConverter jsonYaml;
    private final TomlConverter toml;
    private final CsvConverter csv;

    DocumentFormatter(JsonYamlConverter jsonYaml, TomlConverter toml, CsvConverter csv) {
        this.jsonYaml = jsonYaml;
        this.toml = toml;
        this.csv = csv;
    }

    /** Pretty-prints or canonicalizes input in its own format: the Format action. */
    String format(String input, String fmt, ConversionOptions opts) throws Exception {
        input = TextDecoder.stripBom(input);
        String formatted = switch (fmt) {
            case FMT_JSON  -> formatJson(JsonRepair.autoClose(input), opts.sortKeys());
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
            // Line endings are matched as "\r?\n" and the file's own kind is
            // kept: anchored on "\n" alone, a CRLF file opened from disk was
            // returned untouched, trailing blanks and all.
            case FMT_PROTO -> withoutTrailingBlanks(input)
                                   .replaceAll("(\r?\n)(?:\r?\n){2,}", "$1$1").trim();
            default        -> input;
        };
        // JSON, YAML and TOML sort inside their own formatters above, because
        // all three pass through the JSON tree there and the sort has to happen
        // while the tree exists.
        return formatted;
    }

    /**
     * Every line without the spaces and tabs it ends with, line breaks kept.
     * Linear: the regex this replaces tried every start position of a run of
     * blanks that no line break followed, and a line with 40,000 spaces in it
     * took 25 seconds.
     */
    static String withoutTrailingBlanks(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) != '\n') continue;
            int end = i > lineStart && text.charAt(i - 1) == '\r' ? i - 1 : i;
            int kept = end;
            while (kept > lineStart && (text.charAt(kept - 1) == ' ' || text.charAt(kept - 1) == '\t')) kept--;
            out.append(text, lineStart, kept).append(text, end, i);
            if (i < text.length()) out.append('\n');
            lineStart = i + 1;
        }
        return out.toString();
    }

    /**
     * Re-indents JSON, writing every number exactly as the document spelled it.
     *
     * <p>Through the ordinary tree a number is kept by value, and Format wrote
     * that value back its own way: {@code 1.5e1} came back as {@code 15},
     * {@code 1.0e2} as {@code 1.0E+2} and {@code -0.0} as {@code 0.0}. A float
     * turned integer is a different type to half the JSON readers there are.
     * The document is still read by the lenient reader first, so everything it
     * refuses is refused here too.
     */
    private static String formatJson(String input, boolean sortKeys) throws Exception {
        LenientJson.read(LenientJson.PRETTY, input);
        JsonNode tree;
        try (JsonParser parser = LenientJson.PRETTY.createParser(input)) {
            parser.nextToken();
            tree = literalTree(parser, LenientJson.PRETTY.getNodeFactory());
        }
        return LenientJson.PRETTY.writeValueAsString(sortKeys ? JsonTrees.sorted(tree) : tree);
    }

    private static JsonNode literalTree(JsonParser parser, JsonNodeFactory nodes) throws IOException {
        switch (parser.currentToken()) {
            case START_OBJECT -> {
                ObjectNode object = nodes.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.currentName();
                    parser.nextToken();
                    object.set(name, literalTree(parser, nodes));
                }
                return object;
            }
            case START_ARRAY -> {
                ArrayNode array = nodes.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY)
                    array.add(literalTree(parser, nodes));
                return array;
            }
            // Written back verbatim as a JSON value: the reader already
            // checked it is one.
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                return nodes.rawValueNode(new RawValue(parser.getText()));
            }
            case VALUE_STRING -> { return nodes.textNode(parser.getText()); }
            case VALUE_TRUE -> { return nodes.booleanNode(true); }
            case VALUE_FALSE -> { return nodes.booleanNode(false); }
            default -> { return nodes.nullNode(); }
        }
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
        TomlConverter.forEachValueToken(input, token -> {
            if (TOML_DATE.matcher(token).matches())
                throw new IllegalArgumentException(
                      "Format would rewrite the date " + token + " as a quoted string: "
                      + "TOML has date and time types and the JSON step this uses does not. "
                      + "The document is left as it is.");
            if (TOML_NON_DECIMAL.matcher(token).matches())
                throw new IllegalArgumentException(
                      "Format would rewrite " + token + ": hexadecimal, octal, binary and "
                      + "underscore-separated numbers come back as plain decimals, and inf and nan "
                      + "as text, because the JSON step this uses has no other way to write them. "
                      + "The document is left as it is.");
            if (TOML_DECIMAL_NUMBER.matcher(token).matches())
                rejectRewrittenNumber(token, new BigDecimal(token.startsWith("+") ? token.substring(1) : token));
        });
        String pivot = toml.tomlToJson(input);
        // jsonToToml renders an empty table as the literal "# empty document",
        // which as a FORMAT replaced the user's own comments with that sentence.
        // There is no layout to apply to a document with no values anyway.
        if (LenientJson.PRETTY.readTree(pivot).isEmpty()) return input;
        return toml.jsonToToml(sortKeys ? LenientJson.sortKeys(pivot) : pivot);
    }

    /**
     * Refuses a number the JSON step would write back differently. It keeps a
     * number's value, not its spelling, so {@code 1.5e1} came back as
     * {@code 15} — a float turned integer in a format that tells them apart —
     * and {@code -0.0} as {@code 0.0}. A leading plus sign is the one
     * difference allowed through: it changes neither value nor type.
     */
    static void rejectRewrittenNumber(String written, Object value) {
        String unsigned = written.startsWith("+") ? written.substring(1) : written;
        String rewritten = value.toString();
        if (!rewritten.equals(unsigned))
            throw new IllegalArgumentException(
                  "Format would rewrite the number " + written + " as " + rewritten + ": the JSON "
                  + "step this uses keeps a number's value, not the way it was written. "
                  + "The document is left as it is.");
    }

    // Whole unquoted value tokens supplied by the TOML scanner. Keys, strings,
    // comments and table headers are excluded before these patterns are used.
    private static final Pattern TOML_DATE = Pattern.compile(
          "\\d{4}-\\d{2}-\\d{2}(?:[Tt]\\d{2}:\\d{2}:\\d{2}\\S*)?|\\d{2}:\\d{2}:\\d{2}\\S*");
    private static final Pattern TOML_NON_DECIMAL = Pattern.compile(
          "[+-]?0[xob][0-9A-Fa-f_]+"
          + "|[+-]?(?=[0-9.eE+\\-]*_)[0-9][0-9_.eE+\\-]*"
          + "|[+-]?(?:inf|nan)");
    private static final Pattern TOML_DECIMAL_NUMBER = Pattern.compile(
          "[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    /**
     * What Format would silently discard from a document, as a phrase ("2
     * comments and 1 anchor"), or null when nothing would be lost.
     *
     * <p>Comments survive XML (the DOM keeps them) and Protobuf (whitespace-only
     * tidying). The formats that pass through the JSON tree keep only the data,
     * and the UI asks before it overwrites the editor with less than it held.
     */
    String losses(String input, String fmt) {
        input = TextDecoder.stripBom(input);
        return switch (fmt) {
            case FMT_YAML -> jsonYaml.countFormatLosses(input).describe();
            // A comment-only TOML file is returned untouched by formatToml, so
            // its comments are not at risk.
            case FMT_TOML -> TomlConverter.maskStringsAndComments(input).isBlank() ? null
                  : new FormatLosses(TomlConverter.countComments(input), 0).describe();
            case FMT_JSON -> new FormatLosses(JsonRepair.countComments(input), 0).describe();
            default -> null;
        };
    }

    /**
     * Re-indents XML, writing it back ourselves from a DOM so nothing but the
     * indentation changes.
     *
     * <p>The JDK serializer used before this could not be held to that. It
     * wrote an entity reference it had no definition for as nothing, so every
     * {@code &nbsp;} and {@code &copy;} of an XHTML or DocBook file vanished; it
     * indented a comment inside an otherwise empty element, which changed that
     * element's value; it glued the declaration, a licence comment and the root
     * onto one line and dropped {@code standalone="yes"}; and it recursed until
     * the stack overflowed on deep documents. It also indented text, so a
     * paragraph with a {@code <b>} in it, or anything under
     * {@code xml:space="preserve"}, had to be refused.
     *
     * <p>Only element-only content is re-indented: an element whose children
     * are elements, comments and processing instructions, with nothing but
     * whitespace between them. Everything else — text, a mix of text and
     * elements, CDATA, entity references, a comment alone, and whatever
     * {@code xml:space="preserve"} covers — is written exactly as it was.
     *
     * <p>A DOCTYPE is kept, not fetched: external DTDs and entities are never
     * loaded, and a reference in text to an entity the DTD declares is written
     * back as the reference. What is still refused is an internal subset, which
     * the DOM cannot hand back as written, and such a reference inside an
     * attribute value, which the parser drops. Attributes come back in
     * alphabetical order, the order the DOM holds them in.
     */
    static String prettyXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf =
              javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        dbf.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = dbf.newDocumentBuilder();
        // The default handler prints "[Fatal Error] :1:10: ..." to stderr
        // before the exception is even thrown; the exception is the report.
        builder.setErrorHandler(QUIET_XML_ERRORS);
        org.w3c.dom.Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(xml)));
        org.w3c.dom.DocumentType doctype = doc.getDoctype();
        if (doctype != null && doctype.getInternalSubset() != null
              && !doctype.getInternalSubset().isBlank())
            throw new IllegalArgumentException(
                  "Format cannot keep the declarations inside this document's <!DOCTYPE "
                  + doctype.getName() + " [...]>, and the entities they define would be lost "
                  + "with them. The document is left as it is.");
        // Without a DOCTYPE a reference to an undeclared entity does not parse
        // at all, so only a document with one can hold such a reference.
        String dropped = doctype == null ? null : entityInAttribute(xml);
        if (dropped != null)
            throw new IllegalArgumentException(
                  "Format would drop " + dropped + " from an attribute value: an entity from an external "
                  + "DTD, which is never fetched, is kept in text but lost inside an attribute. "
                  + "The document is left as it is.");
        rejectDeepNesting(doc.getDocumentElement());
        doc.getDocumentElement().normalize();

        StringBuilder out = new StringBuilder(xml.length() + xml.length() / 4 + 64);
        String declaration = declaration(xml);
        if (declaration != null) out.append(declaration).append('\n');
        XmlWriter writer = new XmlWriter(out, isXhtml(doc, doctype), "1.1".equals(doc.getXmlVersion()));
        for (org.w3c.dom.Node child = doc.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeType()) {
                case org.w3c.dom.Node.DOCUMENT_TYPE_NODE -> writer.doctype((org.w3c.dom.DocumentType) child);
                case org.w3c.dom.Node.ELEMENT_NODE -> writer.element((org.w3c.dom.Element) child, 0, false);
                case org.w3c.dom.Node.COMMENT_NODE, org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE ->
                      writer.verbatim(child);
                default -> { continue; }
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static final java.util.Set<String> PREDEFINED_ENTITIES = java.util.Set.of("amp", "lt", "gt", "quot", "apos");

    /**
     * The first reference inside an attribute value to an entity other than
     * XML's five, or null. The parser keeps such a reference in text as a node
     * of its own, but drops it from an attribute value without a trace, so the
     * writer could only lose it: an XHTML page's {@code title="&copy; 2024"}
     * came back as {@code title=" 2024"}. The document has parsed by the time
     * this runs, and its internal subset was refused, so the markup scanned
     * here is well-formed.
     */
    static String entityInAttribute(String xml) {
        int n = xml.length();
        for (int i = 0; ; ) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt + 1 >= n) return null;
            if (xml.startsWith("!--", lt + 1)) { i = after(xml, "-->", lt + 4); continue; }
            if (xml.startsWith("![CDATA[", lt + 1)) { i = after(xml, "]]>", lt + 9); continue; }
            if (xml.charAt(lt + 1) == '?') { i = after(xml, "?>", lt + 2); continue; }
            // A tag, or the DOCTYPE, whose quoted identifiers are no attribute
            // values. A quoted value may hold '>', so it is skipped whole.
            boolean doctype = xml.charAt(lt + 1) == '!';
            int j = lt + 1;
            while (j < n && xml.charAt(j) != '>') {
                char c = xml.charAt(j);
                if (c != '"' && c != '\'') {
                    j++;
                    continue;
                }
                int close = xml.indexOf(c, j + 1);
                if (close < 0) return null;
                String reference = doctype ? null : entityReference(xml, j + 1, close);
                if (reference != null) return reference;
                j = close + 1;
            }
            i = j + 1;
        }
    }

    private static int after(String xml, String end, int from) {
        int at = xml.indexOf(end, from);
        return at < 0 ? xml.length() : at + end.length();
    }

    /** The first {@code &name;} in {@code xml[from, to)} that is no character reference or predefined entity. */
    private static String entityReference(String xml, int from, int to) {
        // Searched within the value only. Unbounded, each search ran on to the
        // next '&' in the document, or its end, for every attribute value, and
        // checking a 6.5 MB file of attributes took over a minute.
        for (int amp = xml.indexOf('&', from, to); amp >= 0; amp = xml.indexOf('&', amp + 1, to)) {
            int semicolon = xml.indexOf(';', amp, to);
            if (semicolon < 0) return null;
            String name = xml.substring(amp + 1, semicolon);
            if (!name.startsWith("#") && !PREDEFINED_ENTITIES.contains(name)) return "&" + name + ";";
        }
        return null;
    }

    private static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";

    /**
     * Whether a document is XHTML: by an XHTML 1.x public identifier or, as
     * XHTML5 and EPUB 3 write it under a bare {@code <!DOCTYPE html>}, by the
     * namespace of its root element.
     */
    private static boolean isXhtml(org.w3c.dom.Document doc, org.w3c.dom.DocumentType doctype) {
        if (doctype != null && doctype.getPublicId() != null && doctype.getPublicId().contains("XHTML")) return true;
        // The parser is not namespace-aware, so xmlns is an attribute like any other.
        org.w3c.dom.Element root = doc.getDocumentElement();
        String tag = root.getTagName();
        int colon = tag.indexOf(':');
        return XHTML_NAMESPACE.equals(root.getAttribute(colon < 0 ? "xmlns" : "xmlns:" + tag.substring(0, colon)));
    }

    /** Reports parse problems through the exception alone, never on stderr. */
    private static final org.xml.sax.ErrorHandler QUIET_XML_ERRORS = new org.xml.sax.ErrorHandler() {
        @Override public void warning(org.xml.sax.SAXParseException e) { }
        @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
    };

    /** The declaration's version and standalone, as written; the encoding is what the plugin saves in. */
    private static final Pattern XML_DECLARATION = Pattern.compile(
          "<\\?xml\\s+version\\s*=\\s*([\"'])([^\"']*)\\1"
          + "(?:\\s+encoding\\s*=\\s*([\"'])[^\"']*\\3)?"
          + "(?:\\s+standalone\\s*=\\s*([\"'])(yes|no)\\4)?\\s*\\?>");

    /**
     * The declaration to write, or null when the document had none. Only
     * {@code <?xml} followed by its version counts: {@code <?xml-stylesheet?>}
     * is a processing instruction, and a document that began with one gained a
     * declaration it never had.
     */
    static String declaration(String xml) {
        java.util.regex.Matcher m = XML_DECLARATION.matcher(xml);
        if (!m.lookingAt()) return null;
        return "<?xml version=\"" + m.group(2) + "\" encoding=\"UTF-8\""
              + (m.group(5) == null ? "" : " standalone=\"" + m.group(5) + "\"") + "?>";
    }

    /** The nesting Format writes, which is the nesting conversion reads. */
    static final int MAX_XML_DEPTH = 1_000;

    private static void rejectDeepNesting(org.w3c.dom.Element root) {
        java.util.ArrayDeque<org.w3c.dom.Node> pending = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        pending.push(root);
        depths.push(1);
        while (!pending.isEmpty()) {
            org.w3c.dom.Node node = pending.pop();
            int depth = depths.pop();
            if (depth > MAX_XML_DEPTH)
                throw new IllegalArgumentException(
                      "Format stops at " + String.format(java.util.Locale.ROOT, "%,d", MAX_XML_DEPTH) + " levels of nesting, "
                      + "as conversion does, and this document goes deeper. The document is left as it is.");
            for (org.w3c.dom.Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
                if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    pending.push(child);
                    depths.push(depth + 1);
                }
        }
    }

    /** Writes DOM nodes back as XML text, indenting element-only content by two spaces. */
    private static final class XmlWriter {
        /**
         * HTML's void elements. Under an XHTML DOCTYPE only these are written
         * minimized, with the space XHTML 1.0's HTML compatibility guidelines
         * ask for: an HTML parser reads {@code <script src="a.js" />} or
         * {@code <div />} as an opening tag, so the rest of the page became
         * script, or went inside the div. The serializer this replaces
         * minimized every empty element.
         */
        private static final java.util.Set<String> HTML_VOID_ELEMENTS = java.util.Set.of(
              "area", "base", "basefont", "br", "col", "embed", "frame", "hr", "img", "input",
              "isindex", "link", "meta", "param", "source", "track", "wbr");

        private final StringBuilder out;
        private final boolean xhtml;
        private final boolean xml11;

        XmlWriter(StringBuilder out, boolean xhtml, boolean xml11) {
            this.out = out;
            this.xhtml = xhtml;
            this.xml11 = xml11;
        }

        void element(org.w3c.dom.Element element, int depth, boolean verbatim) {
            out.append('<').append(element.getTagName());
            org.w3c.dom.NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                org.w3c.dom.Node attribute = attributes.item(i);
                out.append(' ').append(attribute.getNodeName()).append("=\"");
                escape(attribute.getNodeValue(), true);
                out.append('"');
            }
            if (!element.hasChildNodes()) {
                String name = element.getTagName();
                if (!xhtml) out.append("/>");
                else if (HTML_VOID_ELEMENTS.contains(name.substring(name.indexOf(':') + 1))) out.append(" />");
                else out.append("></").append(name).append('>');
                return;
            }
            out.append('>');
            if (!verbatim && indents(element)) {
                for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE) continue;   // whitespace only
                    out.append('\n').append("  ".repeat(depth + 1));
                    if (child instanceof org.w3c.dom.Element nested) element(nested, depth + 1, false);
                    else verbatim(child);
                }
                out.append('\n').append("  ".repeat(depth));
            } else {
                for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
                    verbatim(child);
            }
            out.append("</").append(element.getTagName()).append('>');
        }

        /**
         * Whether an element's children may be put on lines of their own: it
         * holds at least one element, its other children are comments,
         * processing instructions and whitespace, and it did not ask for its
         * whitespace to be kept. Whitespace between elements is not part of any
         * value; anywhere else it is.
         */
        private static boolean indents(org.w3c.dom.Element element) {
            if ("preserve".equals(element.getAttribute("xml:space"))) return false;
            boolean elements = false;
            for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                switch (child.getNodeType()) {
                    case org.w3c.dom.Node.ELEMENT_NODE -> elements = true;
                    case org.w3c.dom.Node.COMMENT_NODE, org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE -> { }
                    case org.w3c.dom.Node.TEXT_NODE -> {
                        if (!xmlWhitespace(child.getNodeValue())) return false;
                    }
                    default -> { return false; }   // CDATA and entity references are content
                }
            }
            return elements;
        }

        /** XML whitespace only: Java also calls significant characters such as U+2028 whitespace. */
        private static boolean xmlWhitespace(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return false;
            }
            return true;
        }

        /** A node exactly as it was, and everything inside it. */
        void verbatim(org.w3c.dom.Node node) {
            switch (node.getNodeType()) {
                case org.w3c.dom.Node.ELEMENT_NODE -> element((org.w3c.dom.Element) node, 0, true);
                case org.w3c.dom.Node.TEXT_NODE -> escape(node.getNodeValue(), false);
                case org.w3c.dom.Node.CDATA_SECTION_NODE ->
                      out.append("<![CDATA[").append(node.getNodeValue()).append("]]>");
                case org.w3c.dom.Node.COMMENT_NODE -> out.append("<!--").append(node.getNodeValue()).append("-->");
                case org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE -> {
                    String data = node.getNodeValue();
                    out.append("<?").append(node.getNodeName())
                          .append(data == null || data.isEmpty() ? "" : " " + data).append("?>");
                }
                case org.w3c.dom.Node.ENTITY_REFERENCE_NODE -> out.append('&').append(node.getNodeName()).append(';');
                default -> { }
            }
        }

        void doctype(org.w3c.dom.DocumentType doctype) {
            out.append("<!DOCTYPE ").append(doctype.getName());
            if (doctype.getPublicId() != null)
                out.append(" PUBLIC ").append(quoted(doctype.getPublicId())).append(' ')
                      .append(quoted(doctype.getSystemId()));
            else if (doctype.getSystemId() != null)
                out.append(" SYSTEM ").append(quoted(doctype.getSystemId()));
            out.append('>');
        }

        private static String quoted(String literal) {
            return literal.contains("\"") ? "'" + literal + "'" : "\"" + literal + "\"";
        }

        /**
         * Escapes what a parser would otherwise read as markup. In an attribute
         * a tab, newline or carriage return is written as a character
         * reference, because a parser turns a literal one into a space; in text
         * a carriage return is, because it turns a literal one into a newline.
         */
        private void escape(String text, boolean attribute) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // XML 1.1 permits restricted controls only as references. Its
                // NEL and line separator must also stay references: literal
                // characters normalize to LF on the next parse (XML 1.1 sections 2.2, 2.11).
                if (xml11 && ((c < 0x20 && c != '\t' && c != '\n' && c != '\r')
                      || (c >= 0x7F && c <= 0x9F) || c == 0x2028)) {
                    out.append("&#").append((int) c).append(';');
                    continue;
                }
                switch (c) {
                    case '&' -> out.append("&amp;");
                    case '<' -> out.append("&lt;");
                    case '>' -> out.append("&gt;");
                    case '"' -> out.append(attribute ? "&quot;" : "\"");
                    case '\r' -> out.append("&#13;");
                    case '\n' -> out.append(attribute ? "&#10;" : "\n");
                    case '\t' -> out.append(attribute ? "&#9;" : "\t");
                    default -> out.append(c);
                }
            }
        }
    }
}
