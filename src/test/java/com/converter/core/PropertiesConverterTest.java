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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PropertiesConverter")
class PropertiesConverterTest {

    private final PropertiesConverter converter = new PropertiesConverter();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode read(String properties, boolean inferTypes) throws Exception {
        return json.readTree(converter.propertiesToJson(properties, inferTypes));
    }

    private JsonNode tree(String jsonText) throws Exception {
        return json.readTree(jsonText);
    }

    /** What java.util.Properties reads from a text: the oracle the reader is held to. */
    private static Map<String, String> oracle(String text) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        Map<String, String> map = new HashMap<>();
        for (String name : properties.stringPropertyNames()) map.put(name, properties.getProperty(name));
        return map;
    }

    private static Map<String, String> ours(String text) {
        Map<String, String> map = new HashMap<>();
        for (PropertiesConverter.Line line : PropertiesConverter.lines(text))
            if (!line.isComment()) map.put(line.key(), line.value());
        return map;
    }

    // ── Reading, against java.util.Properties ────────────────────────────

    @Test @DisplayName("random texts read exactly as java.util.Properties reads them, refusals included")
    void readsAsPropertiesDoes() throws Exception {
        char[] alphabet = {'a', 'b', 'k', '=', ':', '#', '!', '\\', '\\', '\\', ' ', ' ', '\t', '\f',
              '\n', '\n', '\r', 'u', '0', '1', '9', 'A', 'f', 'F', 'g', 'é', '.', '[', ']', '\u0663'};
        Random random = new Random(20261019);
        for (int run = 0; run < 50_000; run++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) text.append(alphabet[random.nextInt(alphabet.length)]);
            String input = text.toString();
            Map<String, String> expected;
            try {
                expected = oracle(input);
            } catch (IllegalArgumentException refused) {
                assertThatThrownBy(() -> ours(input)).as(escaped(input))
                      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Malformed");
                continue;
            }
            assertThat(ours(input)).as(escaped(input)).isEqualTo(expected);
        }
    }

    private static String escaped(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
              .replace("\f", "\\f");
    }

    @Test @DisplayName("the corners of the line reader: continuations, comments after them, and a backslash at the end")
    void lineReaderCorners() throws Exception {
        for (String input : List.of(
              "a=b\\\n   c", "a=b\\\r\n\tc", "a=b\\\rc", "a=b\\", "a=b\\\\", "a=b\\\\\\", "\\", "\\\n", "\\\n\n",
              "\\\n#c=1", "\\\n  !c=1\nd=2", "a\\\n#b", "# c\\\nd=1", "  # c\n\t! c\nk=v", "k = v \\\n",
              "k\\ x\\=y\\:z = 1", "k\\\n=v", "=v", ":v", " =v", "k", "k  ", "k \t=\t v", "k==v", "k=:v",
              "k::v", "k v=w", "k\u00a0=v", "\r\n\r\nk=v\r\n", "k=\\u0041\\u00e9", "k=\\t\\n\\r\\f\\x")) {
            assertThat(ours(input)).as(escaped(input)).isEqualTo(oracle(input));
        }
    }

    @Test @DisplayName("a malformed \\uXXXX escape is refused with its line, as Properties refuses it")
    void malformedUnicode() {
        for (String input : List.of("a=\\u12", "a=1\nb=\\u12G4", "a=\\u\u0663\u0663\u0663\u0663", "\\uZZZZ=1")) {
            assertThatThrownBy(() -> oracle(input)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> converter.propertiesToJson(input, false))
                  .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Malformed \\uXXXX");
        }
        assertThatThrownBy(() -> converter.propertiesToJson("a=1\nb=\\u12G4", false)).hasMessageContaining("line 2");
    }

    // ── Properties to JSON ───────────────────────────────────────────────

    @Test @DisplayName("Spring Boot keys nest: dots into objects, [n] into lists, [key] into a key that keeps its dots")
    void springBootNesting() throws Exception {
        String properties = """
              server.port=8080
              server.servlet.context-path=/api
              spring.datasource.url=jdbc:postgresql://localhost:5432/app
              app.servers[0].host=a.example.com
              app.servers[0].port=1
              app.servers[1].host=b.example.com
              logging.level[org.hibernate.SQL]=debug
              """;
        assertThat(read(properties, true)).isEqualTo(tree("""
              {"server":{"port":8080,"servlet":{"context-path":"/api"}},
               "spring":{"datasource":{"url":"jdbc:postgresql://localhost:5432/app"}},
               "app":{"servers":[{"host":"a.example.com","port":1},{"host":"b.example.com"}]},
               "logging":{"level":{"org.hibernate.SQL":"debug"}}}"""));
    }

    @Test @DisplayName("separators, escapes, continuations and comments are read as Properties reads them")
    void syntax() throws Exception {
        String properties = """
              # a comment
              ! another
              a=1
              b : 2
              c 3
              key\\ with\\ spaces = v
              msg = Hello \\
                    World
              path=C:\\\\Users
              uni=caf\\u00e9
              hash=1 # not a comment
              empty=
              """;
        assertThat(read(properties, false)).isEqualTo(tree("""
              {"a":"1","b":"2","c":"3","key with spaces":"v","msg":"Hello World","path":"C:\\\\Users",
               "uni":"café","hash":"1 # not a comment","empty":""}"""));
    }

    @Test @DisplayName("Infer types reads numbers, booleans and null; off, every value stays text")
    void inferTypes() throws Exception {
        String properties = "i=42\nl=9999999999\nd=1.50\nb=true\nn=null\nz=08\ne=\n";
        JsonNode typed = read(properties, true);
        assertThat(typed.get("i").isInt()).isTrue();
        assertThat(typed.get("l").isLong()).isTrue();
        assertThat(typed.get("d").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(typed.get("b").isBoolean()).isTrue();
        assertThat(typed.get("n").isNull()).isTrue();
        assertThat(typed.get("z").asText()).isEqualTo("08");
        assertThat(typed.get("e").asText()).isEmpty();
        JsonNode text = read(properties, false);
        text.forEach(value -> assertThat(value.isTextual()).isTrue());
        assertThat(text.get("d").asText()).isEqualTo("1.50");
    }

    @Test @DisplayName("a key written twice keeps its last value, in its first place")
    void duplicateKeys() throws Exception {
        JsonNode result = read("a=1\nb=2\na=3\n", false);
        assertThat(result).isEqualTo(tree("{\"a\":\"3\",\"b\":\"2\"}"));
        assertThat(result.fieldNames().next()).isEqualTo("a");
    }

    @Test @DisplayName("lists fill in index order, with null for the gaps below the highest index")
    void lists() throws Exception {
        assertThat(read("a[1]=y\na[0]=x\n", false)).isEqualTo(tree("{\"a\":[\"x\",\"y\"]}"));
        assertThat(read("a[2]=x\n", false)).isEqualTo(tree("{\"a\":[null,null,\"x\"]}"));
        assertThat(read("m[0][1]=x\n", false)).isEqualTo(tree("{\"m\":[[null,\"x\"]]}"));
        assertThat(read("a[007]=x\n", false)).isEqualTo(tree("{\"a\":[null,null,null,null,null,null,null,\"x\"]}"));
        assertThat(read("a[10000]=x\n", false).get("a")).hasSize(10_001);
    }

    @Test @DisplayName("brackets at the start, empty names and a [ with no ] keep to one reading")
    void unusualKeys() throws Exception {
        assertThat(read("[x.y]=1\n", false)).isEqualTo(tree("{\"x.y\":\"1\"}"));
        assertThat(read("[0]=a\n", false)).isEqualTo(tree("{\"0\":\"a\"}"));
        assertThat(read("a.0=x\n", false)).isEqualTo(tree("{\"a\":{\"0\":\"x\"}}"));
        assertThat(read("a.=x\n", false)).isEqualTo(tree("{\"a\":{\"\":\"x\"}}"));
        assertThat(read("a..b=x\n", false)).isEqualTo(tree("{\"a\":{\"\":{\"b\":\"x\"}}}"));
        assertThat(read("=x\n", false)).isEqualTo(tree("{\"\":\"x\"}"));
        assertThat(read("a[b=x\n", false)).isEqualTo(tree("{\"a[b\":\"x\"}"));
        assertThat(read("a[]=x\n", false)).isEqualTo(tree("{\"a\":{\"\":\"x\"}}"));
        assertThat(read("a[x]b=1\n", false)).isEqualTo(tree("{\"a\":{\"x\":{\"b\":\"1\"}}}"));
    }

    @Test @DisplayName("a key that needs a value and keys at one place is refused, naming both keys")
    void clashesAreRefused() {
        assertThatThrownBy(() -> converter.propertiesToJson("a=1\na.b=2\n", false))
              .hasMessageContaining("The key a.b on line 2 cannot be kept beside the key a")
              .hasMessageContaining("needs named keys at a, which already holds a single value");
        assertThatThrownBy(() -> converter.propertiesToJson("a.b=2\na=1\n", false))
              .hasMessageContaining("The key a on line 2 cannot be kept beside the key a.b")
              .hasMessageContaining("needs a value at a, which already holds named keys");
        assertThatThrownBy(() -> converter.propertiesToJson("a[0]=x\na.b=y\n", false))
              .hasMessageContaining("beside the key a[0]").hasMessageContaining("which already holds a list");
        assertThatThrownBy(() -> converter.propertiesToJson("a.b=y\na[0]=x\n", false))
              .hasMessageContaining("needs a list at a, which already holds named keys");
        assertThatThrownBy(() -> converter.propertiesToJson("[x.y]=1\n[x.y].z=2\n", false))
              .hasMessageContaining("at [x.y]");
        // An explicit null is a value, not a gap to build into.
        assertThatThrownBy(() -> converter.propertiesToJson("a[1]=null\na[1].b=2\n", true))
              .hasMessageContaining("needs named keys at a[1], which already holds a single value");
    }

    @Test @DisplayName("an index past 10,000 is refused instead of filling memory with nulls")
    void hugeIndicesAreRefused() {
        for (String input : List.of("a[10001]=x", "a[99999999999999999999]=x", "a[0][2147483648]=x"))
            assertThatThrownBy(() -> converter.propertiesToJson(input, false))
                  .hasMessageContaining("indices above 10,000 are refused");
    }

    @Test @DisplayName("empty input, or comments alone, is refused rather than read as {}")
    void nothingToRead() {
        assertThatThrownBy(() -> converter.propertiesToJson("  \n", false)).hasMessageContaining("empty");
        assertThatThrownBy(() -> converter.propertiesToJson("# only\n! comments\n", false))
              .hasMessageContaining("only comments");
    }

    @Test @DisplayName("a byte-order mark is not part of the first key")
    void byteOrderMark() throws Exception {
        assertThat(read("\uFEFFa=1\n", false)).isEqualTo(tree("{\"a\":\"1\"}"));
    }

    // ── JSON to properties ───────────────────────────────────────────────

    @Test @DisplayName("JSON flattens to Spring Boot keys")
    void flattening() throws Exception {
        String out = converter.jsonToProperties("""
              {"server":{"port":8080,"ssl":true},"app":{"servers":[{"host":"a"},{"host":"b"}],"ratio":1.50},
               "logging":{"level":{"org.hibernate.SQL":"debug"}},"none":null}""");
        assertThat(out).isEqualTo("""
              server.port=8080
              server.ssl=true
              app.servers[0].host=a
              app.servers[1].host=b
              app.ratio=1.50
              logging.level[org.hibernate.SQL]=debug
              none=
              """);
    }

    @Test @DisplayName("keys and values are escaped as Properties.store escapes them, and read back unchanged")
    void escaping() throws Exception {
        String out = converter.jsonToProperties("""
              {"a key":" leading","x=y:z":"a=b:c","#hash":"#not a comment","!bang":"x",
               "tab":"a\\tb\\nc\\rd\\fe","back\\\\slash":"C:\\\\dir\\\\","ctl":"\\u0001\\u007f","uni":"café ☕"}""");
        assertThat(out).contains("a\\ key=\\ leading\n", "x\\=y\\:z=a=b:c\n", "\\#hash=#not a comment\n",
              "\\!bang=x\n", "tab=a\\tb\\nc\\rd\\fe\n", "back\\\\slash=C:\\\\dir\\\\\n", "ctl=\\u0001\\u007F\n",
              // Spring Boot and Gradle read properties as ISO-8859-1.
              "uni=caf\\u00E9 \\u2615\n");
        Map<String, String> back = oracle(out);
        assertThat(back).containsEntry("a key", " leading").containsEntry("x=y:z", "a=b:c")
              .containsEntry("#hash", "#not a comment").containsEntry("tab", "a\tb\nc\rd\fe")
              .containsEntry("back\\slash", "C:\\dir\\").containsEntry("ctl", "\u0001\u007f")
              .containsEntry("uni", "café ☕");
    }

    @Test @DisplayName("an empty object or list has no key to write, and is written as a comment saying so")
    void emptyContainers() throws Exception {
        assertThat(converter.jsonToProperties("{\"a\":{},\"b\":[],\"c\":{\"d\":[]}}")).isEqualTo("""
              # a is an empty object, which properties cannot hold
              # b is an empty list, which properties cannot hold
              # c.d is an empty list, which properties cannot hold
              """);
        assertThat(converter.jsonToProperties("{}")).startsWith("#");
        // A line break in a key named by such a comment must not end the comment and start an entry.
        assertThat(oracle(converter.jsonToProperties("{\"x\\ny=1\":{}}"))).isEmpty();
    }

    @Test @DisplayName("a root that is not an object, or a key no properties key can hold, is refused")
    void unwritable() {
        assertThatThrownBy(() -> converter.jsonToProperties("[1,2]")).hasMessageContaining("not a list");
        assertThatThrownBy(() -> converter.jsonToProperties("42")).hasMessageContaining("not a single value");
        assertThatThrownBy(() -> converter.jsonToProperties("{\"a]b\":1}")).hasMessageContaining("a ]");
        assertThatThrownBy(() -> converter.jsonToProperties("{\"x\":{\"a.b]\":1}}")).hasMessageContaining("a ]");
    }

    @Test @DisplayName("an empty key holding keys or a list is written so that it reads back as itself")
    void emptyTopLevelKey() throws Exception {
        String out = converter.jsonToProperties("{\"\":{\"x.y\":1,\"b\":[2]},\"z\":3}");
        assertThat(out).isEqualTo("[][x.y]=1\n[].b[0]=2\nz=3\n");
        assertThat(read(out, true)).isEqualTo(tree("{\"\":{\"x.y\":1,\"b\":[2]},\"z\":3}"));
        assertThat(read(converter.jsonToProperties("{\"\":[\"a\"]}"), false)).isEqualTo(tree("{\"\":[\"a\"]}"));
        assertThat(converter.jsonToProperties("{\"\":\"v\"}")).isEqualTo("=v\n");
    }

    @Test @DisplayName("random JSON documents of text survive JSON -> properties -> JSON unchanged")
    void roundTrip() throws Exception {
        Random random = new Random(42);
        for (int run = 0; run < 3_000; run++) {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            int keys = 1 + random.nextInt(4);
            for (int k = 0; k < keys; k++) {
                root.set(name(random), value(random, 0));
            }
            String properties = converter.jsonToProperties(json.writeValueAsString(root));
            assertThat(read(properties, false)).as(properties).isEqualTo(root);
        }
    }

    private static String name(Random random) {
        String[] parts = {"a", "b", "0", "12", ".", "[", " ", "=", ":", "#", "!", "\\", "\t", "\n", "é", "☕", "-", "_"};
        StringBuilder name = new StringBuilder();
        int length = random.nextInt(4);
        for (int i = 0; i < length; i++) name.append(parts[random.nextInt(parts.length)]);
        return name.toString();
    }

    private static JsonNode value(Random random, int depth) {
        int kind = depth > 3 ? 0 : random.nextInt(4);
        if (kind == 0 || kind == 3 && depth > 2) {
            String[] parts = {"x", " ", "  ", "=", ":", "#", "!", "\\", "\t", "\n", "\r", "\f", "é", "☕", "\u0001",
                  "\uD83D\uDE00", "true", "1"};
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(5);
            for (int i = 0; i < length; i++) text.append(parts[random.nextInt(parts.length)]);
            return JsonNodeFactory.instance.textNode(text.toString());
        }
        if (kind == 1 || kind == 3) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            int size = 1 + random.nextInt(3);
            for (int i = 0; i < size; i++) object.set(name(random), value(random, depth + 1));
            return object;
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        int size = 1 + random.nextInt(3);
        for (int i = 0; i < size; i++) array.add(value(random, depth + 1));
        return array;
    }

    // ── Format ───────────────────────────────────────────────────────────

    @Test @DisplayName("Format writes key=value, joins continuations, keeps comments and one blank line per gap")
    void format() {
        String input = "# header\n\n\n  a = 1\nb:2\nc   3\n! note\nmsg = Hello \\\n      World\n\n\n\nd=\\ x  \n";
        assertThat(converter.format(input, false)).isEqualTo(
              "# header\n\na=1\nb=2\nc=3\n! note\nmsg=Hello World\n\nd=\\ x  ");
    }

    @Test @DisplayName("Format keeps each key and value as written, UTF-8 text and \\uXXXX escapes alike")
    void formatKeepsEscapes() {
        assertThat(converter.format("k = caf\\u00e9\nj : café\nx\\ y  =\\  a\\=b\\:c \nn\\\n  ext = 1", false))
              .isEqualTo("k=caf\\u00e9\nj=café\nx\\ y=\\  a\\=b\\:c \nnext=1");
    }

    @Test @DisplayName("Format with Sort keys moves each entry with the comments above it, the header staying on top")
    void formatSorted() {
        String input = "# licence\n\n# the port\nserver.port=8080\napp.name=demo\n# twice\nb=1\nb=2\n\n# trailing\n";
        assertThat(converter.format(input, true)).isEqualTo(
              "# licence\n\napp.name=demo\n# twice\nb=1\nb=2\n# the port\nserver.port=8080\n\n# trailing");
    }

    @Test @DisplayName("Format never changes what a random text reads as")
    void formatKeepsMeaning() throws Exception {
        char[] alphabet = {'a', 'b', '=', ':', '#', '!', '\\', '\\', ' ', ' ', '\t', '\n', '\n', '\r', 'u', '0',
              '4', '1', 'é', '.', '[', ']', '\u0001'};
        Random random = new Random(5);
        for (int run = 0; run < 20_000; run++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) text.append(alphabet[random.nextInt(alphabet.length)]);
            String input = text.toString();
            Map<String, String> before;
            try {
                before = oracle(input);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            for (boolean sort : new boolean[]{false, true}) {
                String formatted = converter.format(input, sort);
                assertThat(oracle(formatted)).as(escaped(input) + " -> " + escaped(formatted)).isEqualTo(before);
                assertThat(converter.format(formatted, sort)).as("idempotent: " + escaped(input)).isEqualTo(formatted);
            }
        }
    }
}
