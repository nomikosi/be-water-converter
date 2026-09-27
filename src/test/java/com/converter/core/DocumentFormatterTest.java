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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/** Format re-lays-out a document in its own format; it must never change what the document says. */
@DisplayName("Format")
class DocumentFormatterTest {

    private final ConversionPipeline pipeline = new ConversionPipeline();
    private final ConversionOptions opts = ConversionOptions.DEFAULTS;
    private final ObjectMapper json = new ObjectMapper();

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

    @Nested @DisplayName("JSON")
    class Json {

        @Test @DisplayName("formatInput pretty-prints truncated JSON via autoClose")
        void formatInputJson() throws Exception {
            String result = pipeline.formatInput("{\"a\":1", Formats.FMT_JSON, ConversionOptions.DEFAULTS.withInferTypes(true));
            assertThat(json.readTree(result).get("a").intValue()).isEqualTo(1);
        }

        @Test @DisplayName("Format writes every number exactly as the document spelled it")
        void numbersKeepTheirSpelling() throws Exception {
            String input = "{\"ratio\": 1.5e1, \"unit\": 1e0, \"c\": 1.0e2, \"n\": -0.0, \"price\": 1.10,"
                  + " \"huge\": 1e400, \"big\": 12345678901234567890123, \"tiny\": 1E-7}";
            for (ConversionOptions options : new ConversionOptions[]{opts, opts.withSortKeys(true)}) {
                String formatted = pipeline.formatInput(input, Formats.FMT_JSON, options);
                assertThat(formatted).contains(": 1.5e1", ": 1e0", ": 1.0e2", ": -0.0", ": 1.10", ": 1e400",
                      ": 12345678901234567890123", ": 1E-7");
            }
            String sorted = pipeline.formatInput(input, Formats.FMT_JSON, opts.withSortKeys(true));
            assertThat(sorted.indexOf("\"big\"")).isLessThan(sorted.indexOf("\"ratio\""));
        }

        @Test @DisplayName("Format still refuses what the reader refuses")
        void readerRulesStillApply() {
            assertThatThrownBy(() -> pipeline.formatInput("{\"a\":1,\"a\":2}", Formats.FMT_JSON, opts))
                  .hasMessageContaining("Duplicate");
            assertThatThrownBy(() -> pipeline.formatInput("{\"a\":1} {\"b\":2}", Formats.FMT_JSON, opts))
                  .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> pipeline.formatInput("// only a comment", Formats.FMT_JSON, opts))
                  .hasMessageContaining("no value");
        }
    }

    @Nested @DisplayName("YAML")
    class Yaml {

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

        @ParameterizedTest @ValueSource(strings = {
              "1: value\n",
              "true: value\n",
              "null: value\n",
              "1.5: value\n",
              "? [a, b]\n: value\n",
              "? {a: b}\n: value\n",
              "rows:\n  - true: value\n",
              "defaults: &d {1: x}\ncopy: {<<: *d}\n",
              "!!timestamp 2024-01-01",
              "v: !!timestamp 2024-01-01T12:30:00Z",
              "v: !!set {a: null}",
              "v: !!omap [{a: 1}, {b: 2}]",
              "v: !!pairs [{a: 1}, {a: 2}]"
        })
        void refusesYamlTypesThatFormattingCannotPreserve(String input) {
            assertThatThrownBy(() -> pipeline.formatInput(input, "YAML", ConversionOptions.DEFAULTS.withInferTypes(false)))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Format cannot preserve").hasMessageContaining("left as it is");
        }

        @ParameterizedTest @ValueSource(strings = {
              "\"1\": value\n\"true\": other\n\"null\": text\n",
              "v: \"!!timestamp 2024-01-01\"\n",
              "a: !!str 123\nb: !!int 42\nc: !!bool true\nd: !!null null\n",
              "v: !!binary SGVsbG8=\n",
              "base: &d {a: 1.10}\ncopy: {<<: *d, b: true}\n",
              "null\n---\nrows: [{z: 1.10, a: yes}]\n"
        })
        void supportedValuesAndStringKeysKeepTheirMeaning(String input) throws Exception {
            for (boolean sort : new boolean[]{false, true}) {
                String formatted = pipeline.formatInput(input, "YAML", ConversionOptions.DEFAULTS.withSortKeys(sort));
                assertThat(pipeline.canonicalJson(formatted, "YAML", ConversionOptions.DEFAULTS)).isEqualTo(pipeline.canonicalJson(input, "YAML", ConversionOptions.DEFAULTS));
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0x1F", "0b101", "1_000", "1_000.5", "1.5e1", "1.0e2", "-0.0", "-0", ".5", "1."})
        @DisplayName("Format refuses a number it would write back differently")
        void refusesRewrittenNumbers(String number) {
            assertThatThrownBy(() -> pipeline.formatInput("a: " + number + "\n", Formats.FMT_YAML, opts))
                  .hasMessageContaining("Format would rewrite the number " + number);
        }

        @Test @DisplayName("numbers written the way JSON writes them format as before")
        void plainNumbersStillFormat() throws Exception {
            assertThat(pipeline.formatInput("a: 1.10\nb: +1\nc: 1E+2\nd: -12\n", Formats.FMT_YAML, opts))
                  .isEqualTo("a: 1.10\nb: 1\nc: 1E+2\nd: -12\n");
        }
    }

    @Nested @DisplayName("TOML")
    class Toml {

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

        @ParameterizedTest @ValueSource(strings = {
              "[2024-01-01]\nx = 1\n",
              "[0xFF]\nx = 1\n",
              "[inf]\nx = 1\n",
              "[1_000]\nx = 1\n",
              "[[nan]]\nx = 1\n",
              "x = {inf = 1, 2024-01-01 = 2, 0xFF = 3}\n",
              "inf = \"1979-05-27t07:32:00Z\"\n",
              "x = \"\"\"a \\\"\"\" b\"\"\"\n[2024-01-01]\ny = 1\n"
        })
        void keysAndQuotedTextDoNotTriggerValueGuards(String input) throws Exception {
            String formatted = pipeline.formatInput(input, "TOML", ConversionOptions.DEFAULTS.withInferTypes(false));
            assertThat(pipeline.canonicalJson(formatted, "TOML", ConversionOptions.DEFAULTS)).isEqualTo(pipeline.canonicalJson(input, "TOML", ConversionOptions.DEFAULTS));
        }

        @ParameterizedTest @ValueSource(strings = {
              "d = 1979-05-27t07:32:00Z",
              "d = 1979-05-27T07:32:00Z",
              "d = 1979-05-27 07:32:00Z",
              "d = 1979-05-27",
              "d = 07:32:00",
              "d = [1, 1979-05-27t07:32:00Z]",
              "d = {v = 1979-05-27t07:32:00Z}",
              "d = [[1, 0xFF]]",
              "d = {v = -inf}",
              "d = [1, 1_000]",
              "text = \"\"\"a \\\"\"\" b\"\"\"\nd = 1979-05-27t07:32:00Z"
        })
        void refusesLossyValuesInEveryContainer(String input) {
            assertThatThrownBy(() -> pipeline.formatInput(input, "TOML", ConversionOptions.DEFAULTS.withInferTypes(false)))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Format would rewrite");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"5e+22", "6.626e-34", "1.5e1", "-0.0", "-0"})
        @DisplayName("Format refuses a number it would write back differently")
        void refusesRewrittenNumbers(String number) {
            assertThatThrownBy(() -> pipeline.formatInput("a = " + number + "\n", Formats.FMT_TOML, opts))
                  .hasMessageContaining("Format would rewrite the number " + number);
        }

        @Test @DisplayName("numbers written the way JSON writes them format as before")
        void plainNumbersStillFormat() throws Exception {
            assertThat(pipeline.formatInput("a = 1.10\nb = +1.5\nc = 1E+2\nd = -12\n", Formats.FMT_TOML, opts))
                  .isEqualTo("a = 1.10\nb = 1.5\nc = 1E+2\nd = -12\n");
        }
    }

    @Nested @DisplayName("XML")
    class Xml {

        @Test @DisplayName("Format writes mixed content as it was and keeps the declaration as written")
        void xmlFormatMixedContentAndDeclaration() throws Exception {
            // The JDK serializer indented text too, so a paragraph with a <b> in
            // it had to be refused. Only element-only content is indented now.
            assertThat(pipeline.formatInput("<p>Hello <b>big</b> world</p>", Formats.FMT_XML, opts))
                  .isEqualTo("<p>Hello <b>big</b> world</p>\n");
            assertThat(pipeline.formatInput("<r><d>text <e>in</e> mixed</d></r>", Formats.FMT_XML, opts))
                  .isEqualTo("<r>\n  <d>text <e>in</e> mixed</d>\n</r>\n");
            // standalone="no" was appended to a declaration that never had it.
            String out = pipeline.formatInput("<?xml version=\"1.0\"?><a><b>x</b></a>",
                  Formats.FMT_XML, opts);
            assertThat(out).startsWith("<?xml").doesNotContain("standalone");
            // Element-only content with formatting whitespace is not mixed content.
            assertThat(pipeline.formatInput("<r>\n  <a>1</a>\n  <b>  spaced  </b>\n</r>",
                  Formats.FMT_XML, opts))
                  .contains("<a>1</a>").contains("<b>  spaced  </b>");
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

        @Test @DisplayName("prettyXml preserves the original root element and attributes")
        void prettyXmlPreservesRoot() throws Exception {
            String result = DocumentFormatter.prettyXml(
                  "<person id=\"7\"><name>Ada</name><langs><l>en</l><l>el</l></langs></person>");
            assertThat(result).startsWith("<person id=\"7\">")
                  .contains("  <name>Ada</name>")
                  .contains("<langs>");
        }

        @Test @DisplayName("prettyXml keeps the XML declaration only when the input had one")
        void prettyXmlDeclaration() throws Exception {
            assertThat(DocumentFormatter.prettyXml("<?xml version=\"1.0\"?><r><a>1</a></r>"))
                  .startsWith("<?xml");
            assertThat(DocumentFormatter.prettyXml("<r><a>1</a></r>"))
                  .doesNotContain("<?xml");
        }

        @Test @DisplayName("prettyXml rejects DOCTYPE declarations (XXE hardening)")
        void prettyXmlRejectsDoctype() {
            assertThatThrownBy(() -> DocumentFormatter.prettyXml(
                  "<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><foo>&x;</foo>"))
                  .isInstanceOf(Exception.class);
        }

        @Test @DisplayName("Format keeps whitespace that is an element's whole value")
        void whitespaceValuesSurvive() throws Exception {
            String input = "<r><sep> </sep><indent>    </indent><b>1</b></r>";
            String formatted = pipeline.formatInput(input, Formats.FMT_XML, opts);
            assertThat(formatted).contains("<sep> </sep>").contains("<indent>    </indent>");
            assertThat(pipeline.normalizeToJson(formatted, Formats.FMT_XML, opts))
                  .isEqualTo(pipeline.normalizeToJson(input, Formats.FMT_XML, opts));
        }

        @Test @DisplayName("Format leaves content declared xml:space=\"preserve\" exactly as written")
        void preservedSpaceIsNotReindented() throws Exception {
            assertThat(pipeline.formatInput("<r xml:space=\"preserve\"><a>1</a> <b>2</b></r>", Formats.FMT_XML, opts))
                  .isEqualTo("<r xml:space=\"preserve\"><a>1</a> <b>2</b></r>\n");
            assertThat(pipeline.formatInput("<r><pre xml:space=\"preserve\">\n <x>1</x>\n</pre><b>1</b></r>",
                  Formats.FMT_XML, opts))
                  .isEqualTo("<r>\n  <pre xml:space=\"preserve\">\n <x>1</x>\n</pre>\n  <b>1</b>\n</r>\n");
            // A preserved leaf has nothing to indent inside it, so it formats as written.
            String leaf = pipeline.formatInput("<r><pre xml:space=\"preserve\">  two  spaces  </pre><b>1</b></r>",
                  Formats.FMT_XML, opts);
            assertThat(leaf).contains("<pre xml:space=\"preserve\">  two  spaces  </pre>");
        }
    }

    @Nested @DisplayName("CSV")
    class Csv {

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
            // Blank lines are layout, and are tidied; the file keeps its own
            // line breaks, and CRLF is the one RFC 4180 names.
            assertThat(pipeline.formatInput("a,b\r\n1,2\r\n\r\n3,4\r\n", Formats.FMT_CSV, opts))
                  .isEqualTo("a,b\r\n1,2\r\n3,4\r\n");
            // Content that needs quotes keeps them.
            assertThat(pipeline.formatInput("a,b\n\"x,y\",\"q\"\"q\"\n", Formats.FMT_CSV, opts))
                  .isEqualTo("a,b\n\"x,y\",\"q\"\"q\"\n");
        }
    }

    @Nested @DisplayName("Protobuf")
    class Protobuf {

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

        @Test @DisplayName("formatInput collapses excess blank lines in proto schemas")
        void formatInputProto() throws Exception {
            String result = pipeline.formatInput(
                  "message A {\n  string x = 1;   \n\n\n\n}", Formats.FMT_PROTO, ConversionOptions.DEFAULTS.withInferTypes(true));
            assertThat(result).doesNotContain("\n\n\n");
        }
    }

    @Nested @DisplayName("XML as written")
    class XmlAsWritten {

        @Test @DisplayName("entity references an external DTD declares are written back as references")
        void externalEntitiesSurvive() throws Exception {
            // The JDK serializer wrote a reference it had no definition for as
            // nothing: every &nbsp; and &copy; of an XHTML page vanished.
            String xhtml = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" "
                  + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
                  + "<html><body><p>Copyright &copy; 2024 ACME&nbsp;Corp</p><td>&nbsp;</td></body></html>";
            assertThat(pipeline.formatInput(xhtml, Formats.FMT_XML, opts))
                  .contains("<p>Copyright &copy; 2024 ACME&nbsp;Corp</p>").contains("<td>&nbsp;</td>");
        }

        @Test @DisplayName("under XHTML only HTML's void elements are minimized; elsewhere every empty element is")
        void xhtmlEmptyElements() throws Exception {
            // <script src="a.js" /> is an opening tag to an HTML parser: the
            // rest of the page became script.
            String xhtml = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" "
                  + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
                  + "<html><head><script src=\"a.js\"></script><meta charset=\"utf-8\"/></head>"
                  + "<body><p>a<br/>b</p><div/></body></html>";
            assertThat(pipeline.formatInput(xhtml, Formats.FMT_XML, opts))
                  .contains("<script src=\"a.js\"></script>").contains("<meta charset=\"utf-8\" />")
                  .contains("<p>a<br />b</p>").contains("<div></div>");
            assertThat(pipeline.formatInput("<r><a></a><b/></r>", Formats.FMT_XML, opts))
                  .isEqualTo("<r>\n  <a/>\n  <b/>\n</r>\n");
        }

        @Test @DisplayName("the prolog keeps standalone, a line per node, and no invented declaration")
        void prologAsWritten() throws Exception {
            assertThat(pipeline.formatInput(
                  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<r><a>1</a></r>", Formats.FMT_XML, opts))
                  .isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<r>\n  <a>1</a>\n</r>\n");
            assertThat(pipeline.formatInput(
                  "<?xml version=\"1.0\"?>\n<!-- licence -->\n<project><a>1</a></project>", Formats.FMT_XML, opts))
                  .isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- licence -->\n<project>\n  <a>1</a>\n</project>\n");
            // <?xml-stylesheet?> is a processing instruction, not a declaration.
            assertThat(pipeline.formatInput(
                  "<?xml-stylesheet type=\"text/xsl\" href=\"s.xsl\"?>\n<r><a>1</a></r>", Formats.FMT_XML, opts))
                  .isEqualTo("<?xml-stylesheet type=\"text/xsl\" href=\"s.xsl\"?>\n<r>\n  <a>1</a>\n</r>\n");
        }

        @Test @DisplayName("an element holding only a comment converts to the same value after Format")
        void commentOnlyElementsKeepTheirValue() throws Exception {
            String input = "<r><a><!-- todo --></a><b>1</b></r>";
            String formatted = pipeline.formatInput(input, Formats.FMT_XML, opts);
            assertThat(formatted).contains("<a><!-- todo --></a>");
            assertThat(pipeline.normalizeToJson(formatted, Formats.FMT_XML, opts))
                  .isEqualTo(pipeline.normalizeToJson(input, Formats.FMT_XML, opts));
        }

        @Test @DisplayName("escaped text, CDATA and attribute line breaks read back the same")
        void escapingRoundTrips() throws Exception {
            String input = "<r a=\"x &amp; &quot;y&quot; &#10;z\"><t>1 &lt; 2 &amp;&amp; 3 &gt; 2</t>"
                  + "<c><![CDATA[<raw> & ]]></c></r>";
            String formatted = pipeline.formatInput(input, Formats.FMT_XML, opts);
            assertThat(formatted).contains("<![CDATA[<raw> & ]]>").contains("&#10;");
            assertThat(pipeline.normalizeToJson(formatted, Formats.FMT_XML, opts))
                  .isEqualTo(pipeline.normalizeToJson(input, Formats.FMT_XML, opts));
        }

        @Test @DisplayName("a document deeper than conversion reads is refused, not a stack overflow")
        void deepDocumentsAreRefused() {
            String deep = "<a>".repeat(5_000) + "</a>".repeat(5_000);
            assertThatThrownBy(() -> pipeline.formatInput(deep, Formats.FMT_XML, opts))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("1,000 levels");
        }
    }

    @Nested @DisplayName("Protobuf layout")
    class ProtobufLayout {

        @Test @DisplayName("trailing blanks go in linear time, line breaks kept")
        void trailingBlanks() throws Exception {
            assertThat(pipeline.formatInput("message A {   \r\n  int32 a = 1;\t\r\n}\r\n", Formats.FMT_PROTO, opts))
                  .isEqualTo("message A {\r\n  int32 a = 1;\r\n}");
            // Quadratic before: 40,000 blanks in mid-line took 25 seconds.
            String wide = "message A {" + " ".repeat(200_000) + "int32 a = 1; }\n";
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                  () -> pipeline.formatInput(wide, Formats.FMT_PROTO, opts));
        }
    }
}
