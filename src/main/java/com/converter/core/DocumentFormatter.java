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

import java.io.StringReader;
import java.io.StringWriter;
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
            case FMT_JSON  -> LenientJson.pretty(JsonRepair.autoClose(input));
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
            case FMT_PROTO -> input.replaceAll("[ \t]+(?=\r?\n)", "")
                                   .replaceAll("(\r?\n)(?:\r?\n){2,}", "$1$1").trim();
            default        -> input;
        };
        // YAML and TOML sort inside their own formatters above, because both
        // already pass through the JSON tree there and the sort has to happen
        // while the tree exists.
        if (opts.sortKeys() && FMT_JSON.equals(fmt)) return LenientJson.pretty(LenientJson.sortKeys(formatted));
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
        });
        String pivot = toml.tomlToJson(input);
        // jsonToToml renders an empty table as the literal "# empty document",
        // which as a FORMAT replaced the user's own comments with that sentence.
        // There is no layout to apply to a document with no values anyway.
        if (LenientJson.PRETTY.readTree(pivot).isEmpty()) return input;
        return toml.jsonToToml(sortKeys ? LenientJson.sortKeys(pivot) : pivot);
    }

    // Whole unquoted value tokens supplied by the TOML scanner. Keys, strings,
    // comments and table headers are excluded before these patterns are used.
    private static final Pattern TOML_DATE = Pattern.compile(
          "\\d{4}-\\d{2}-\\d{2}(?:[Tt]\\d{2}:\\d{2}:\\d{2}\\S*)?|\\d{2}:\\d{2}:\\d{2}\\S*");
    private static final Pattern TOML_NON_DECIMAL = Pattern.compile(
          "[+-]?0[xob][0-9A-Fa-f_]+"
          + "|[+-]?(?=[0-9.eE+\\-]*_)[0-9][0-9_.eE+\\-]*"
          + "|[+-]?(?:inf|nan)");

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
     * Pretty-prints XML via DOM + Transformer so the original root element,
     * attributes and structure are preserved (Jackson's tree model drops the
     * root element name).
     *
     * <p>A DOCTYPE is kept, not fetched: external DTDs and entities are never
     * loaded. The declaration used to be disallowed outright, which refused a
     * plist, an XHTML page or an SVG with the parser's own sentence about a
     * feature flag — while Convert read the same file without complaint. What
     * is still refused is an internal subset, because the DOM path drops the
     * entity references it declares, and a serialised {@code <!DOCTYPE>} cannot
     * carry the subset back.
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
        // Without this the serializer appends standalone="no" to a declaration
        // the document wrote without it.
        doc.setXmlStandalone(true);
        doc.getDocumentElement().normalize();
        rejectMixedContent(doc.getDocumentElement());
        stripIndentation(doc.getDocumentElement(), false);

        javax.xml.transform.TransformerFactory tf =
              javax.xml.transform.TransformerFactory.newInstance();
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        javax.xml.transform.Transformer t = tf.newTransformer();
        // Said explicitly: for a root element named html the identity
        // transformer switches to its HTML output method on its own, which
        // dropped the XML declaration, renamed the DOCTYPE and wrote <br/> as
        // <br> — an XHTML page came back as something no XML parser accepts.
        t.setOutputProperty(javax.xml.transform.OutputKeys.METHOD, "xml");
        t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        boolean declared = xml.stripLeading().startsWith("<?xml");
        t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, declared ? "no" : "yes");
        if (doctype != null && doctype.getPublicId() != null)
            t.setOutputProperty(javax.xml.transform.OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId());
        if (doctype != null && doctype.getSystemId() != null)
            t.setOutputProperty(javax.xml.transform.OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId());

        StringWriter out = new StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(doc),
              new javax.xml.transform.stream.StreamResult(out));
        String result = out.toString();
        // The serializer writes a DOCTYPE only when it has an identifier to
        // put in it; a bare <!DOCTYPE html> would otherwise vanish.
        if (doctype != null && doctype.getPublicId() == null && doctype.getSystemId() == null) {
            int afterDeclaration = declared && result.startsWith("<?xml") ? result.indexOf("?>") + 2 : 0;
            String head = result.substring(0, afterDeclaration);
            String tail = result.substring(afterDeclaration).stripLeading();
            result = head + (head.isEmpty() ? "" : "\n") + "<!DOCTYPE " + doctype.getName() + ">\n" + tail;
        }
        return result;
    }

    /** Reports parse problems through the exception alone, never on stderr. */
    private static final org.xml.sax.ErrorHandler QUIET_XML_ERRORS = new org.xml.sax.ErrorHandler() {
        @Override public void warning(org.xml.sax.SAXParseException e) { }
        @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
    };

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

    /**
     * Removes the whitespace between child elements, so re-indenting doesn't
     * stack blank lines.
     *
     * <p>Only BETWEEN elements: whitespace that is an element's whole content
     * is its value. {@code <sep> </sep>} came back as {@code <sep/>}, and
     * converting that read {@code ""} where the document said {@code " "}.
     *
     * <p>{@code xml:space="preserve"} makes the whitespace between children
     * content too. The indenter cannot be told to leave one subtree alone, so an
     * element that asks for it and has children to indent is refused.
     *
     * @param preserve whether an enclosing element asked for preservation
     */
    private static void stripIndentation(org.w3c.dom.Element element, boolean preserve) {
        String space = element.getAttribute("xml:space");
        if ("preserve".equals(space)) preserve = true;
        else if ("default".equals(space)) preserve = false;

        org.w3c.dom.NodeList children = element.getChildNodes();
        boolean indented = false;
        for (int i = 0; i < children.getLength(); i++) {
            short type = children.item(i).getNodeType();
            indented |= type == org.w3c.dom.Node.ELEMENT_NODE || type == org.w3c.dom.Node.COMMENT_NODE
                  || type == org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE;
        }
        if (!indented) return;   // a leaf: its text is its value, whitespace included
        if (preserve)
            throw new IllegalArgumentException(
                  "Format would re-indent <" + element.getTagName() + ">, whose whitespace is "
                  + "declared significant with xml:space=\"preserve\". The document is left as it is.");
        for (int i = children.getLength() - 1; i >= 0; i--) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE && child.getTextContent().isBlank())
                element.removeChild(child);
            else if (child instanceof org.w3c.dom.Element nested)
                stripIndentation(nested, preserve);
        }
    }
}
