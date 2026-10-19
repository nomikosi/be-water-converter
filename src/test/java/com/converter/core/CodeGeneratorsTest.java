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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * What the TypeScript, Dart, Go, Python and Rust generators write, line for
 * line. That the output compiles, and reads and writes its JSON, is the
 * Generated*Test classes' part.
 */
@DisplayName("Code generators")
class CodeGeneratorsTest {

    /** A key renamed, a list, nested and merged objects, a key one element lacks, a null, and an empty object. */
    static final String SAMPLE = """
          {"id":1,"first-name":"Ada","tags":["a"],"nested":{"ratio":1.5},"items":[{"a":1,"b":"x"},{"a":2}],\
          "none":null,"meta":{}}""";

    @Nested @DisplayName("names from a key's words")
    class Words {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
              "userID|user id", "HTTPServer|http server", "imageURLs|image urls", "userIDs|user ids",
              "IDsList|ids list", "AString|a string", "first-name|first name", "x2Y|x2 y", "a__b|a b",
              "ABC|abc", "snake_case_key|snake case key", "'名前'|''", "é|''", "'$ref'|ref"})
        void words(String key, String words) {
            assertThat(String.join(" ", SourceConventions.words(key))).isEqualTo(words);
        }

        @Test void cases() {
            assertThat(SourceConventions.snakeCase("firstName")).isEqualTo("first_name");
            assertThat(SourceConventions.pascalCase("first-name")).isEqualTo("FirstName");
            assertThat(SourceConventions.lowerCamelCase("First_Name")).isEqualTo("firstName");
            assertThat(SourceConventions.typeNameFromWords("1st")).isEqualTo("Type1st");
            assertThat(SourceConventions.typeNameFromWords("@")).isEqualTo("Type");
        }
    }

    @Nested @DisplayName("TypeScript")
    class TypeScript {
        @Test void interfaces() throws Exception {
            assertThat(new TypeScriptGenerator().fromJson(SAMPLE)).isEqualTo("""
                  export interface Root {
                    id: number;
                    "first-name": string;
                    tags: string[];
                    nested: Nested;
                    items: Items[];
                    none: unknown;
                    meta: Record<string, unknown>;
                  }

                  export interface Nested {
                    ratio: number;
                  }

                  export interface Items {
                    a: number;
                    b?: string | null;
                  }
                  """);
        }

        @Test void keysAreQuotedNotRenamed() throws Exception {
            assertThat(new TypeScriptGenerator().fromJson("{\"a\\\"b\":1,\"$ok\":2,\"class\":3,\"\":4,\"x\\ny\":5}"))
                  .contains("  \"a\\\"b\": number;", "  $ok: number;", "  class: number;", "  \"\": number;",
                        "  \"x\\ny\": number;");
        }

        @Test void arrays() throws Exception {
            assertThat(new TypeScriptGenerator().fromJson("{\"xs\":[1,null],\"m\":[[1]],\"e\":[],\"mixed\":[1,\"a\"]}"))
                  .contains("xs: (number | null)[];", "m: number[][];", "e: unknown[];", "mixed: unknown[];");
        }

        @Test void anEmptyRootIsAnEmptyRecord() throws Exception {
            assertThat(new TypeScriptGenerator().fromJson("{}")).isEqualTo("export type Root = Record<string, never>;\n");
        }

        @Test void aRecordShadowingTypeIsRenamed() throws Exception {
            assertThat(new TypeScriptGenerator().fromJson("{\"record\":{\"a\":1},\"x\":{}}"))
                  .contains("record: RecordValue;", "export interface RecordValue {", "x: Record<string, unknown>;");
        }
    }

    @Nested @DisplayName("Rust")
    class Rust {
        @Test void structs() throws Exception {
            assertThat(new RustSerdeGenerator().fromJson(SAMPLE)).isEqualTo("""
                  use serde::{Deserialize, Serialize};

                  #[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
                  pub struct Root {
                      pub id: i64,
                      #[serde(rename = "first-name")]
                      pub first_name: String,
                      pub tags: Vec<String>,
                      pub nested: Nested,
                      pub items: Vec<Items>,
                      pub none: serde_json::Value,
                      pub meta: serde_json::Map<String, serde_json::Value>,
                  }

                  #[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
                  pub struct Nested {
                      pub ratio: f64,
                  }

                  #[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
                  pub struct Items {
                      pub a: i64,
                      #[serde(skip_serializing_if = "Option::is_none")]
                      pub b: Option<String>,
                  }
                  """);
        }

        @Test void keywordsCollisionsAndNumbers() throws Exception {
            String out = new RustSerdeGenerator().fromJson(
                  "{\"type\":1,\"self\":2,\"a_b\":3,\"aB\":4,\"1st\":5,\"big\":18446744073709551615,"
                        + "\"huge\":18446744073709551616,\"items\":[{\"v\":null,\"n\":null},{\"n\":1}]}");
            assertThat(out).contains("#[serde(rename = \"type\")]\n    pub type_: i64,",
                  "#[serde(rename = \"self\")]\n    pub self_: i64,", "    pub a_b: i64,",
                  "#[serde(rename = \"aB\")]\n    pub a_b_2: i64,", "#[serde(rename = \"1st\")]\n    pub field_1st: i64,",
                  "pub big: u64,", "pub huge: f64,",
                  "#[serde(default, skip_serializing_if = \"serde_json::Value::is_null\")]\n    pub v: serde_json::Value,",
                  "#[serde(skip_serializing_if = \"Option::is_none\")]\n    pub n: Option<i64>,");
        }

        @Test void anEmptyStructAndAnEscapedKey() throws Exception {
            assertThat(new RustSerdeGenerator().fromJson("{}")).endsWith("pub struct Root {}\n");
            assertThat(new RustSerdeGenerator().fromJson("{\"a\\u0001\\\"b\":1}"))
                  .contains("#[serde(rename = \"a\\u{1}\\\"b\")]");
            assertThatThrownBy(() -> new RustSerdeGenerator().fromJson("{\"\\ud800\":1}"))
                  .hasMessageContaining("half of a surrogate pair");
        }
    }

    @Nested @DisplayName("Dart")
    class Dart {
        @Test void classes() throws Exception {
            String out = new DartGenerator().fromJson(SAMPLE);
            assertThat(out).startsWith("""
                  class Root {
                    final int id;
                    final String firstName;
                    final List<String> tags;
                    final Nested nested;
                    final List<Items> items;
                    final dynamic none;
                    final Map<String, dynamic> meta;

                    const Root({
                      required this.id,
                      required this.firstName,
                      required this.tags,
                      required this.nested,
                      required this.items,
                      required this.none,
                      required this.meta,
                    });

                    factory Root.fromJson(Map<String, dynamic> json) => Root(
                          id: json['id'] as int,
                          firstName: json['first-name'] as String,
                          tags: (json['tags'] as List<dynamic>).map((e) => e as String).toList(),
                          nested: Nested.fromJson(json['nested'] as Map<String, dynamic>),
                          items: (json['items'] as List<dynamic>).map((e) => Items.fromJson(e as Map<String, dynamic>)).toList(),
                          none: json['none'],
                          meta: json['meta'] as Map<String, dynamic>,
                        );

                    Map<String, dynamic> toJson() => {
                          'id': id,
                          'first-name': firstName,
                          'tags': tags,
                          'nested': nested.toJson(),
                          'items': items.map((e) => e.toJson()).toList(),
                          'none': none,
                          'meta': meta,
                        };
                  }
                  """);
            assertThat(out).contains("""
                  class Items {
                    final int a;
                    final String? b;

                    const Items({
                      required this.a,
                      this.b,
                    });

                    factory Items.fromJson(Map<String, dynamic> json) => Items(
                          a: json['a'] as int,
                          b: json['b'] as String?,
                        );

                    Map<String, dynamic> toJson() => {
                          'a': a,
                          if (b != null) 'b': b,
                        };
                  }
                  """);
        }

        @Test void numbersNullsAndNesting() throws Exception {
            String out = new DartGenerator().fromJson(
                  "{\"d\":1.5,\"big\":12345678901234567890,\"m\":[[1.5]],\"os\":[{\"v\":1},null],"
                        + "\"opt\":[{\"n\":{\"x\":1}},{}]}");
            assertThat(out).contains("d: (json['d'] as num).toDouble(),", "big: json['big'] as num,",
                  "m: (json['m'] as List<dynamic>).map((e) => (e as List<dynamic>).map((e1) => (e1 as num).toDouble()).toList()).toList(),",
                  "os: (json['os'] as List<dynamic>).map((e) => e == null ? null : Os.fromJson(e as Map<String, dynamic>)).toList(),",
                  "'os': os.map((e) => e?.toJson()).toList(),",
                  "n: json['n'] == null ? null : N.fromJson(json['n'] as Map<String, dynamic>),",
                  "if (n != null) 'n': n!.toJson(),");
        }

        @Test void reservedNamesAndLiterals() throws Exception {
            String out = new DartGenerator().fromJson(
                  "{\"class\":1,\"hashCode\":2,\"toJson\":3,\"1st\":4,\"\":5,\"$ref\":6,\"it's\":7,\"List\":{\"a\":1}}");
            assertThat(out).contains("final int classValue;", "final int hashCodeValue;", "final int toJsonValue;",
                  "final int field1st;", "final int value;", "json['\\$ref']", "json['it\\'s']",
                  "final ListValue list;", "class ListValue {");
        }

        @Test void anEmptyClass() throws Exception {
            assertThat(new DartGenerator().fromJson("{}")).isEqualTo("""
                  class Root {
                    const Root();

                    factory Root.fromJson(Map<String, dynamic> json) => const Root();

                    Map<String, dynamic> toJson() => {};
                  }
                  """);
        }
    }

    @Nested @DisplayName("Go")
    class Go {
        @Test void structs() throws Exception {
            assertThat(new GoStructGenerator().fromJson(SAMPLE)).isEqualTo(String.join("\n",
                  "package main",
                  "",
                  "type Root struct {",
                  "\tID        int            `json:\"id\"`",
                  "\tFirstName string         `json:\"first-name\"`",
                  "\tTags      []string       `json:\"tags\"`",
                  "\tNested    Nested         `json:\"nested\"`",
                  "\tItems     []Items        `json:\"items\"`",
                  "\tNone      any            `json:\"none\"`",
                  "\tMeta      map[string]any `json:\"meta\"`",
                  "}",
                  "",
                  "type Nested struct {",
                  "\tRatio float64 `json:\"ratio\"`",
                  "}",
                  "",
                  "type Items struct {",
                  "\tA int     `json:\"a\"`",
                  "\tB *string `json:\"b,omitempty\"`",
                  "}",
                  ""));
        }

        @Test void namesInitialismsAndNumbers() throws Exception {
            String out = new GoStructGenerator().fromJson(
                  "{\"user_id\":1,\"imageURLs\":[\"u\"],\"apiKey\":\"k\",\"1st\":true,\"-\":1,\"long\":9007199254740993,"
                        + "\"big\":12345678901234567890,\"pi\":3.14159265358979323846264338327950288,"
                        + "\"xs\":[1,null],\"opts\":[{\"o\":[1]},{}]}");
            assertThat(out).contains("import \"encoding/json\"", "UserID ", "ImageURLs ", "APIKey ", "Field1st ",
                  "`json:\"-,\"`", "int64 ", "Big ", "json.Number", "[]*int ", "`json:\"o,omitempty\"`");
            assertThat(out.lines().filter(line -> line.contains("Pi ")).findFirst().orElseThrow()).contains("json.Number");
        }

        @Test void anEmptyStruct() throws Exception {
            assertThat(new GoStructGenerator().fromJson("{}")).isEqualTo("package main\n\ntype Root struct{}\n");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {"a b|true", "$ref|true", "x.y|true", "'名前'|true", "''|false",
              "a,b|false", "it's|false", "back\\slash|false", "emoji😀|false"})
        void tagNames(String key, boolean valid) {
            assertThat(GoStructGenerator.isValidTagName(key)).isEqualTo(valid);
        }
    }

    @Nested @DisplayName("Python")
    class Python {
        @Test void models() throws Exception {
            assertThat(new PydanticGenerator().fromJson(SAMPLE)).isEqualTo("""
                  from typing import Any

                  from pydantic import BaseModel, Field


                  class Items(BaseModel):
                      a: int
                      b: str | None = None


                  class Nested(BaseModel):
                      ratio: float


                  class Root(BaseModel):
                      id: int
                      first_name: str = Field(alias="first-name")
                      tags: list[str]
                      nested: Nested
                      items: list[Items]
                      none: Any
                      meta: dict[str, Any]
                  """);
        }

        @Test void reservedNamesAndDates() throws Exception {
            String out = new PydanticGenerator().fromJson(
                  "{\"class\":1,\"list\":2,\"json\":3,\"model_dump\":4,\"model_x\":5,\"day\":\"2024-05-01\","
                        + "\"at\":\"2024-05-01T10:00:00Z\",\"items\":[{\"b\":1},{\"c\":2}]}", true);
            assertThat(out).startsWith("from datetime import date, datetime\n")
                  .contains("class_: int = Field(alias=\"class\")", "list_: int = Field(alias=\"list\")",
                        "json_: int = Field(alias=\"json\")", "model_dump_: int = Field(alias=\"model_dump\")",
                        "    model_config = ConfigDict(protected_namespaces=())\n\n    class_: int",
                        "day: date", "at: datetime", "b: int | None = None", "c: int | None = None");
            assertThat(new PydanticGenerator().fromJson("{\"day\":\"2024-05-01\"}", false)).contains("day: str");
        }

        @Test void literals() {
            assertThat(PydanticGenerator.stringLiteral("a\"b\\c\nd😀\ud800")).isEqualTo("\"a\\\"b\\\\c\\nd😀\\ud800\"");
        }

        @Test void anEmptyModel() throws Exception {
            assertThat(new PydanticGenerator().fromJson("{}"))
                  .isEqualTo("from pydantic import BaseModel\n\n\nclass Root(BaseModel):\n    pass\n");
        }
    }

    @Test @DisplayName("a root array is typed from its merged elements, and a scalar or empty array refused")
    void rootsEveryGeneratorShares() throws Exception {
        for (Generator generator : GENERATORS) {
            assertThat(generator.generate("[{\"a\":1},{\"b\":2}]")).as(generator.name()).contains("Root");
            assertThatThrownBy(() -> generator.generate("42")).as(generator.name())
                  .hasMessageContaining("Nothing to generate");
            assertThatThrownBy(() -> generator.generate("[]")).as(generator.name()).hasMessageContaining("empty");
            assertThatThrownBy(() -> generator.generate(" ")).as(generator.name()).hasMessageContaining("blank");
        }
    }

    private interface Generator {
        String generate(String json) throws Exception;

        default String name() { return getClass().getSimpleName(); }
    }

    private static final List<Generator> GENERATORS = List.of(
          json -> new TypeScriptGenerator().fromJson(json), json -> new RustSerdeGenerator().fromJson(json),
          json -> new DartGenerator().fromJson(json), json -> new GoStructGenerator().fromJson(json),
          json -> new PydanticGenerator().fromJson(json));
}
