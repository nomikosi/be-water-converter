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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Format re-lays-out a document; it must never change what the document says. */
@DisplayName("Format and detection")
class FormatAndDetectionTest {

    private final ConversionPipeline pipeline = new ConversionPipeline();
    private final ConversionOptions opts = ConversionOptions.DEFAULTS;

    @Test @DisplayName("Format keeps a multi-document YAML file multi-document")
    void multiDocumentYamlSurvivesFormat() throws Exception {
        // The stream became a JSON array and came back as one sequence, so two
        // manifests were replaced by a single list.
        String input = "kind: Service\nname: a\n---\nkind: ConfigMap\nname: b\n";
        String out = pipeline.formatInput(input, Formats.FMT_YAML, opts);
        assertThat(out).contains("---");
        assertThat(out).contains("kind: Service").contains("kind: ConfigMap");
        // Not a sequence: no document should have been turned into a list item.
        assertThat(out.stripLeading()).doesNotStartWith("-");
        // Round-trips back to two documents rather than one.
        assertThat(pipeline.normalizeToJson(out, Formats.FMT_YAML, opts))
              .startsWith("[").contains("Service").contains("ConfigMap");
    }

    @Test @DisplayName("Format leaves a single YAML document unwrapped")
    void singleDocumentYamlUnchanged() throws Exception {
        assertThat(pipeline.formatInput("a: 1\n", Formats.FMT_YAML, opts))
              .contains("a: 1").doesNotContain("---");
    }

    @Test @DisplayName("Format keeps a ----prefixed single document as one document")
    void leadingSeparatorIsNotAStreamOfDocuments() throws Exception {
        // The text scan for "---" also matched the optional start marker, so a
        // single sequence document was split into one document per element:
        // "---\n- a\n- b" came back as "a\n---\nb".
        String out = pipeline.formatInput("---\n- a\n- b\n", Formats.FMT_YAML, opts);
        assertThat(out).doesNotContain("---");
        assertThat(pipeline.normalizeToJson(out, Formats.FMT_YAML, opts))
              .isEqualTo("[\"a\",\"b\"]");
        // Nor is a "---" line inside a block scalar a separator.
        String block = "- |\n  x\n  ---\n  y\n- b\n";
        assertThat(pipeline.normalizeToJson(
              pipeline.formatInput(block, Formats.FMT_YAML, opts),
              Formats.FMT_YAML, opts)).isEqualTo("[\"x\\n---\\ny\\n\",\"b\"]");
    }

    @Test @DisplayName("Format keeps YAML floats as written, and refuses .inf and .nan")
    void yamlFormatKeepsFloats() throws Exception {
        assertThat(pipeline.formatInput("price: 1.10\ntotal: 100.00\n", Formats.FMT_YAML, opts))
              .contains("price: 1.10").contains("total: 100.00");
        // JSON has no infinity, so the only thing Format could write back is text.
        assertThatThrownBy(() -> pipeline.formatInput("a: .inf\n", Formats.FMT_YAML, opts))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Infinity");
        assertThatThrownBy(() -> pipeline.formatInput("a: [1, .nan]\n", Formats.FMT_YAML, opts))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("Format keeps TOML floats as written, and refuses literals JSON cannot spell")
    void tomlFormatKeepsNumbers() throws Exception {
        assertThat(pipeline.formatInput("a = 1.10\n", Formats.FMT_TOML, opts))
              .contains("a = 1.10");
        // 0xFF came back as 255, 1_000 as 1000 and inf as the STRING 'Infinity'.
        for (String doc : new String[]{"a = 0xFF\n", "a = 0o17\n", "a = 0b101\n", "a = 1_000\n",
              "a = 1_000.5\n", "a = inf\n", "a = -inf\n", "a = nan\n", "a = [1, 2_0]\n",
              "t = {x = 0xA}\n"}) {
            assertThatThrownBy(() -> pipeline.formatInput(doc, Formats.FMT_TOML, opts))
                  .describedAs(doc)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Format would rewrite");
        }
        // Keys with underscores, and literals inside strings or comments, are not values.
        assertThat(pipeline.formatInput("my_key = 1\ns = \"0xFF\" # 1_000\n",
              Formats.FMT_TOML, opts)).contains("my_key = 1");
    }

    @Test @DisplayName("Format refuses XML mixed content and keeps the declaration as written")
    void xmlFormatMixedContentAndDeclaration() throws Exception {
        // The serializer indents text nodes too, so the text of a paragraph
        // gained line breaks and indentation — a content change, not layout.
        assertThatThrownBy(() -> pipeline.formatInput("<p>Hello <b>big</b> world</p>",
              Formats.FMT_XML, opts))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("<p>");
        assertThatThrownBy(() -> pipeline.formatInput("<r><d>text <e>in</e> mixed</d></r>",
              Formats.FMT_XML, opts))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("<d>");
        // standalone="no" was appended to a declaration that never had it.
        String out = pipeline.formatInput("<?xml version=\"1.0\"?><a><b>x</b></a>",
              Formats.FMT_XML, opts);
        assertThat(out).startsWith("<?xml").doesNotContain("standalone");
        // Element-only content with formatting whitespace is not mixed content.
        assertThat(pipeline.formatInput("<r>\n  <a>1</a>\n  <b>  spaced  </b>\n</r>",
              Formats.FMT_XML, opts))
              .contains("<a>1</a>").contains("<b>  spaced  </b>");
    }

    @Test @DisplayName("Format keeps CSV headers and ragged rows exactly")
    void csvFormatIsPositional() throws Exception {
        // Through the pivot, headers were renamed (id,id,, became id,id_2,column_3),
        // a ragged row dropped the headers it lacked (a,b,c\n1 became a\n1), and
        // a header-only file was refused as having nothing to write.
        assertThat(pipeline.formatInput("id,id,,name\n1,2,3,4\n", Formats.FMT_CSV, opts))
              .isEqualTo("id,id,,name\n1,2,3,4\n");
        assertThat(pipeline.formatInput("a,b,c\n1\n", Formats.FMT_CSV, opts))
              .isEqualTo("a,b,c\n1\n");
        assertThat(pipeline.formatInput("a,b\n", Formats.FMT_CSV, opts))
              .isEqualTo("a,b\n");
        // A row with more cells than headers is kept as well: nothing is discarded.
        assertThat(pipeline.formatInput("a,b\n1,2,3\n", Formats.FMT_CSV, opts))
              .isEqualTo("a,b\n1,2,3\n");
        // Blank lines and CRLF are layout, and are tidied.
        assertThat(pipeline.formatInput("a,b\r\n1,2\r\n\r\n3,4\r\n", Formats.FMT_CSV, opts))
              .isEqualTo("a,b\n1,2\n3,4\n");
        // Content that needs quotes keeps them.
        assertThat(pipeline.formatInput("a,b\n\"x,y\",\"q\"\"q\"\n", Formats.FMT_CSV, opts))
              .isEqualTo("a,b\n\"x,y\",\"q\"\"q\"\n");
    }

    @Test @DisplayName("formatLosses counts what Format would discard, and nothing else")
    void formatLossesAreCounted() {
        assertThat(pipeline.formatLosses("# top\na: 1 # inline\n\nb:\n  - x\n",
              Formats.FMT_YAML)).isEqualTo("2 comments");
        assertThat(pipeline.formatLosses("base: &b\n  x: 1\nother:\n  <<: *b\n",
              Formats.FMT_YAML)).isEqualTo("1 anchor");
        assertThat(pipeline.formatLosses("# c\nbase: &b {x: 1}\nu: *b\n",
              Formats.FMT_YAML)).isEqualTo("1 comment and 1 anchor");
        // A '#' inside a string is not a comment.
        assertThat(pipeline.formatLosses("a: \"# not a comment\"\n", Formats.FMT_YAML))
              .isNull();
        assertThat(pipeline.formatLosses("a = 1 # note\n# another #hash\ns = \"#\"\n",
              Formats.FMT_TOML)).isEqualTo("2 comments");
        // A comment-only TOML file is returned untouched, so nothing is at risk.
        assertThat(pipeline.formatLosses("# just a note\n", Formats.FMT_TOML)).isNull();
        assertThat(pipeline.formatLosses("{\"a\":1 // one\n, \"b\":\"//\" /* two */}",
              Formats.FMT_JSON)).isEqualTo("2 comments");
        assertThat(pipeline.formatLosses("{\"a\":1}", Formats.FMT_JSON)).isNull();
        // XML keeps its comments through the DOM, so there is nothing to warn about.
        assertThat(pipeline.formatLosses("<r><!-- kept --></r>", Formats.FMT_XML)).isNull();
    }

    @Test @DisplayName("detection stays linear over long runs of blank lines")
    void detectionIsNotQuadraticOverBlankLines() {
        // "^\s*" in the markers ran over every blank line after each line start
        // and backtracked through the run one character at a time: 16,000 blank
        // lines took seven seconds, on the EDT, for a paste.
        String text = "x\n" + "\n".repeat(16_000) + "a: 1\n";
        long started = System.nanoTime();
        assertThat(FormatDetector.detectFormat(text)).isEqualTo(Formats.FMT_YAML);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis).describedAs("detection took %d ms", millis).isLessThan(2_000);
        // The markers still see an indented or a bare line for what it is.
        assertThat(FormatDetector.detectFormat("x\n\n\n  key = 1\n"))
              .isEqualTo(Formats.FMT_TOML);
        assertThat(FormatDetector.detectFormat("x\n-\n  a: 1\n"))
              .isEqualTo(Formats.FMT_YAML);
    }

    @Test @DisplayName("semicolon- and tab-separated CSV are detected, with their delimiter")
    void csvDelimiterDetection() {
        // Only the comma was tried, so a semicolon file was not CSV at all.
        assertThat(FormatDetector.detectFormat("id;name\n1;Ada\n")).isEqualTo(Formats.FMT_CSV);
        assertThat(FormatDetector.detectCsvDelimiter("id;name\n1;Ada\n")).isEqualTo(';');
        assertThat(FormatDetector.detectFormat("id\tname\n1\tAda\n")).isEqualTo(Formats.FMT_CSV);
        assertThat(FormatDetector.detectCsvDelimiter("id\tname\n1\tAda\n")).isEqualTo('\t');
        assertThat(FormatDetector.detectCsvDelimiter("id,name\n1,Ada\n")).isEqualTo(',');
        // A decimal comma inside a semicolon file does not make it comma-separated.
        assertThat(FormatDetector.detectCsvDelimiter("price;qty\n1,5;2\n2,5;3\n")).isEqualTo(';');
        assertThat(FormatDetector.detectCsvDelimiter("just some prose\nwith no delimiter\n")).isNull();
        assertThat(FormatDetector.detectCsvDelimiter("a: 1\nb: 2\n")).isNull();
    }

    @Test @DisplayName("Format refuses to rewrite a TOML date as a string")
    void tomlDatesAreNotRetyped() {
        // TOML has date types and JSON does not, so the pivot stringified them
        // and Format wrote the quoted form back over the user's file.
        assertThatThrownBy(() -> pipeline.formatInput(
              "[meta]\ncreated = 1979-05-27T07:32:00Z\n", Formats.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("quoted string");
        assertThatThrownBy(() -> pipeline.formatInput(
              "day = 1979-05-27\n", Formats.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("Format refuses a TOML date wherever it sits, not only after '='")
    void tomlDatesInAnyPositionAreCaught() {
        // Anchoring on '=' meant only the first array element could match, so
        // d = [1979-05-27] was still rewritten as a quoted string.
        assertThatThrownBy(() -> pipeline.formatInput(
              "d = [1979-05-27]\n", Formats.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pipeline.formatInput(
              "d = [1979-05-27, 1979-05-28]\n", Formats.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pipeline.formatInput(
              "t = {at = 07:32:00}\n", Formats.FMT_TOML, opts))
              .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("Format still tidies TOML that carries no dates")
    void tomlWithoutDatesStillFormats() throws Exception {
        assertThat(pipeline.formatInput("a=1\nb=\"x\"\n", Formats.FMT_TOML, opts))
              .contains("a = 1").contains("b = 'x'");   // the TOML writer quotes with '
        // A date inside a string or a comment is not a date value.
        assertThat(pipeline.formatInput("a = \"1979-05-27\"\n", Formats.FMT_TOML, opts))
              .contains("1979-05-27");
    }

    @Test @DisplayName("Format keeps a comment-only TOML file rather than replacing it")
    void commentOnlyTomlSurvivesFormat() throws Exception {
        // jsonToToml renders an empty table as the literal "# empty document",
        // so Format replaced the user's own comments with that sentence.
        assertThat(pipeline.formatInput("# just a note\n", Formats.FMT_TOML, opts))
              .contains("just a note").doesNotContain("empty document");
    }

    @Test @DisplayName("a source file embedded in YAML is not detected as Protobuf")
    void embeddedPackageLineIsNotProto() {
        // "package x;" is ordinary Java, Kotlin and Go, and the marker was
        // searched for across the whole document.
        String configMap = "apiVersion: v1\nkind: ConfigMap\ndata:\n  Main.java: |\n"
              + "    package com.example;\n    class Main {}\n";
        assertThat(FormatDetector.detectFormat(configMap)).isEqualTo(Formats.FMT_YAML);
    }

    @Test @DisplayName("real Protobuf is still detected")
    void realProtoStillDetected() {
        assertThat(FormatDetector.detectFormat("syntax = \"proto3\";\nmessage P { string a = 1; }"))
              .isEqualTo(Formats.FMT_PROTO);
        assertThat(FormatDetector.detectFormat("message Person {\n  string name = 1;\n}"))
              .isEqualTo(Formats.FMT_PROTO);
        assertThat(FormatDetector.detectFormat("enum Colour {\n  RED = 0;\n}"))
              .isEqualTo(Formats.FMT_PROTO);
    }

    @Test @DisplayName("autoClose leaves input the lenient reader already accepts alone")
    void autoCloseRespectsCommentsAndSingleQuotes() throws Exception {
        // A brace inside a comment or a single-quoted string was counted as
        // real, and the appended closer made valid input fail to parse.
        assertThat(JsonRepair.autoClose("{\"a\":1} // trailing {")).isEqualTo("{\"a\":1} // trailing {");
        assertThat(JsonRepair.autoClose("{'a':'it {'}")).isEqualTo("{'a':'it {'}");
        assertThat(JsonRepair.autoClose("{\"a\":1} /* note [ */")).isEqualTo("{\"a\":1} /* note [ */");
        assertThat(JsonRepair.autoClose("{\"a\":1} # hash {")).isEqualTo("{\"a\":1} # hash {");
        // Genuinely unclosed input is still completed.
        assertThat(JsonRepair.autoClose("{\"a\":[1,2")).isEqualTo("{\"a\":[1,2]}");
        assertThat(JsonRepair.autoClose("{\"a\":\"unterminated")).isEqualTo("{\"a\":\"unterminated\"}");
        // And the completed forms parse.
        assertThat(pipeline.formatInput("{\"a\":1} // trailing {", Formats.FMT_JSON, opts))
              .contains("\"a\"");
    }

    @Test @DisplayName("autoClose puts its closers after a trailing line comment, not inside it")
    void autoCloseClosesAfterTrailingLineComment() throws Exception {
        // {"a":1 // note} is a document whose brace is part of the comment.
        assertThat(JsonRepair.autoClose("{\"a\":1 // note")).isEqualTo("{\"a\":1 // note\n}");
        assertThat(JsonRepair.autoClose("{\"a\":[1 # note")).isEqualTo("{\"a\":[1 # note\n]}");
        assertThat(pipeline.formatInput("{\"a\":1 // note", Formats.FMT_JSON, opts))
              .contains("\"a\" : 1");
        assertThat(pipeline.normalizeToJson("{\"a\":1 // note", Formats.FMT_JSON, opts))
              .isEqualTo("{\"a\":1}");
        // Complete input ending in a comment gains nothing.
        assertThat(JsonRepair.autoClose("{\"a\":1} // note")).isEqualTo("{\"a\":1} // note");
    }

    @Test @DisplayName("a comment-only JSON document is refused, not converted to null")
    void commentOnlyJsonIsRefused() {
        // The reader hands back a missing node for it, which serialised as the
        // document "null" and reported a successful conversion.
        for (String input : new String[]{"// todo", "# todo", "/* todo */", "  \n// a\n// b\n"}) {
            assertThatThrownBy(() -> pipeline.normalizeToJson(input, Formats.FMT_JSON, opts))
                  .describedAs(input)
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("no value");
            assertThatThrownBy(() -> pipeline.formatInput(input, Formats.FMT_JSON, opts))
                  .describedAs(input)
                  .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test @DisplayName("detection looks past leading comment lines")
    void detectionSkipsLeadingComments() {
        assertThat(FormatDetector.detectFormat("// note\n{\"a\": 1}")).isEqualTo(Formats.FMT_JSON);
        assertThat(FormatDetector.detectFormat("# note\n{\"a\": 1}")).isEqualTo(Formats.FMT_JSON);
        assertThat(FormatDetector.detectFormat("/* note\n   more */\n{\"a\": 1}")).isEqualTo(Formats.FMT_JSON);
        // A bracket line after a comment was the TOML table-header check's
        // first line, so a JSON array with a comment above it was TOML.
        assertThat(FormatDetector.detectFormat("# note\n[1, 2]")).isEqualTo(Formats.FMT_JSON);
        assertThat(FormatDetector.detectFormat("# note\n[server]\nport = 1")).isEqualTo(Formats.FMT_TOML);
        assertThat(FormatDetector.detectFormat("# note\n- a\n- b")).isEqualTo(Formats.FMT_YAML);
        assertThat(FormatDetector.detectFormat("// licence\nsyntax = \"proto3\";\nmessage A { int32 x = 1; }"))
              .isEqualTo(Formats.FMT_PROTO);
        // Nothing but comments is nothing.
        assertThat(FormatDetector.detectFormat("# note\n// note")).isNull();
        assertThat(FormatDetector.detectFormat("/* never closed")).isNull();
        // A comment inside the document does not start it over.
        assertThat(FormatDetector.withoutLeadingComments("a: 1\n# c\nb: 2")).isEqualTo("a: 1\n# c\nb: 2");
    }

    @Test @DisplayName("a '#'-prefixed CSV header is a header, not a comment")
    void hashHeadedCsvIsStillCsv() {
        // Skipping leading comments for the other formats took the header off
        // a CSV file that opens with one, and the single line left behind was
        // too little to compare column counts against: a two-line file stopped
        // being detected at all. The convention is ordinary in tab-separated
        // exports, so the CSV check reads the document as written.
        assertThat(FormatDetector.detectFormat("#id,name\n1,Ann")).isEqualTo(Formats.FMT_CSV);
        assertThat(FormatDetector.detectFormat("#id,name\n1,Ann\n2,Bob")).isEqualTo(Formats.FMT_CSV);
        assertThat(FormatDetector.detectFormat("#chrom\tpos\nchr1\t100"))
              .isEqualTo(Formats.FMT_CSV);
        assertThat(FormatDetector.detectFormat("#id;name\n1;Ann")).isEqualTo(Formats.FMT_CSV);
        // The delimiter comes from the same reading, so the panel selects it
        // rather than leaving the combo on whatever was there before.
        assertThat(FormatDetector.detectCsvDelimiter("#chrom\tpos\nchr1\t100")).isEqualTo('\t');
        assertThat(FormatDetector.detectCsvDelimiter("#id;name\n1;Ann")).isEqualTo(';');
        // The other shape: a genuine note above a real header. Both are CSV,
        // so both readings are tried and the delimiter is found either way.
        assertThat(FormatDetector.detectFormat("# exported\nid,name\n1,Ann"))
              .isEqualTo(Formats.FMT_CSV);
        assertThat(FormatDetector.detectCsvDelimiter("# exported\nid;name\n1;Ann")).isEqualTo(';');
        // A commented header block over YAML or TOML must not tip either into
        // CSV: every structural check still runs first, on the stripped text.
        assertThat(FormatDetector.detectFormat("# id, name\n# 1, Ann\nkey: value"))
              .isEqualTo(Formats.FMT_YAML);
        assertThat(FormatDetector.detectFormat("# id, name\n# 1, Ann\n[server]\nport = 1"))
              .isEqualTo(Formats.FMT_TOML);
        assertThat(FormatDetector.detectFormat("# id, name\n// 1, Ann\n{\"a\": 1}"))
              .isEqualTo(Formats.FMT_JSON);
    }

    @Test @DisplayName("Protobuf Format tidies CRLF files too")
    void protoFormatHandlesCrlf() throws Exception {
        // Anchored on "\n" alone, a file with Windows line endings came back
        // untouched, trailing blanks and all.
        String crlf = "message A {\r\n  string a = 1;   \r\n\r\n\r\n\r\n  int32 b = 2;\r\n}\r\n";
        assertThat(pipeline.formatInput(crlf, Formats.FMT_PROTO, opts))
              .isEqualTo("message A {\r\n  string a = 1;\r\n\r\n  int32 b = 2;\r\n}");
        String lf = "message A {\n  string a = 1;   \n\n\n\n  int32 b = 2;\n}\n";
        assertThat(pipeline.formatInput(lf, Formats.FMT_PROTO, opts))
              .isEqualTo("message A {\n  string a = 1;\n\n  int32 b = 2;\n}");
    }

    @Test @DisplayName("XML Format keeps a DOCTYPE without an internal subset")
    void xmlFormatKeepsDoctype() throws Exception {
        // Any DOCTYPE was refused with the parser's sentence about a feature
        // flag, while Convert accepted the same file.
        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
              + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
              + "<plist version=\"1.0\"><dict><key>a</key><string>b</string></dict></plist>";
        String out = pipeline.formatInput(plist, Formats.FMT_XML, opts);
        assertThat(out).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .contains("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                    + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
              .contains("\n    <key>a</key>")
              .doesNotContain("standalone");
        // A system identifier alone, and a bare name alone.
        assertThat(pipeline.formatInput("<!DOCTYPE a SYSTEM \"a.dtd\"><a><b>1</b></a>",
              Formats.FMT_XML, opts))
              .startsWith("<!DOCTYPE a SYSTEM \"a.dtd\">").contains("<b>1</b>");
        assertThat(pipeline.formatInput("<!DOCTYPE html>\n<html><body><p>x</p></body></html>",
              Formats.FMT_XML, opts))
              .startsWith("<!DOCTYPE html>\n<html>");
        assertThat(pipeline.formatInput("<?xml version=\"1.0\"?><!DOCTYPE html><html><p>x</p></html>",
              Formats.FMT_XML, opts))
              .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n<html>");
        // A root named html made the serializer switch to its HTML method:
        // no declaration, the DOCTYPE renamed, and <br/> written as <br>.
        String xhtml = pipeline.formatInput(
              "<?xml version=\"1.0\"?><!DOCTYPE html SYSTEM \"x.dtd\"><html><body><br/></body></html>",
              Formats.FMT_XML, opts);
        assertThat(xhtml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
              .contains("<!DOCTYPE html SYSTEM \"x.dtd\">").contains("<br/>").contains("\n  <body>");
        // An internal subset declares entities the DOM path would drop.
        assertThatThrownBy(() -> pipeline.formatInput(
              "<!DOCTYPE a [<!ENTITY x \"hi\">]><a><b>&x;</b></a>", Formats.FMT_XML, opts))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("<!DOCTYPE a [...]>");
        // Malformed XML still fails through the exception, with the location.
        assertThatThrownBy(() -> pipeline.formatInput("<a><b>1</a>", Formats.FMT_XML, opts))
              .isInstanceOf(org.xml.sax.SAXParseException.class);
    }
}
