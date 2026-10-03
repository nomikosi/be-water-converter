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
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.WireFormat;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Payloads are written with protobuf-java's own encoder, so the tests do not
 * lean on an encoder of their own that could share the decoder's mistakes.
 */
@DisplayName("Protobuf payload decoding")
class ProtoPayloadDecoderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProtoPayloadDecoder decoder = new ProtoPayloadDecoder(new ProtoConverter());

    interface Writes {
        void to(CodedOutputStream out) throws IOException;
    }

    static byte[] bytes(Writes writes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        writes.to(out);
        out.flush();
        return bytes.toByteArray();
    }

    static String hex(Writes writes) throws IOException {
        return HexFormat.of().formatHex(bytes(writes));
    }

    /** A message written as a length-delimited field: its tag, its length, its bytes. */
    static void message(CodedOutputStream out, int field, Writes content) throws IOException {
        byte[] body = bytes(content);
        out.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        out.writeUInt32NoTag(body.length);
        out.writeRawBytes(body);
    }

    private JsonNode raw(String payload) throws Exception {
        return JSON.readTree(decoder.decode(payload, "", ""));
    }

    private JsonNode typed(String payload, String schema, String message) throws Exception {
        return JSON.readTree(decoder.decode(payload, schema, message));
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Nested @DisplayName("without a schema")
    class Raw {

        @Test @DisplayName("the encoding guide's examples")
        void guide() throws Exception {
            // protobuf.dev/programming-guides/encoding
            assertThat(raw("08 96 01")).isEqualTo(json("{\"1\":150}"));
            assertThat(raw("12 07 74 65 73 74 69 6e 67")).isEqualTo(json("{\"2\":\"testing\"}"));
            assertThat(raw("1a 03 08 96 01")).isEqualTo(json("{\"3\":{\"1\":150}}"));
        }

        @Test @DisplayName("varints are unsigned and exact, up to ten bytes")
        void varints() throws Exception {
            assertThat(raw(hex(out -> {
                out.writeUInt64(1, 0);
                out.writeUInt64(2, 127);
                out.writeUInt64(3, 128);
                out.writeUInt64(4, Long.MAX_VALUE);
                out.writeUInt64(5, -1L);
                out.writeInt32(6, -1);            // ten bytes, sign-extended
            }))).isEqualTo(json("{\"1\":0,\"2\":127,\"3\":128,\"4\":9223372036854775807,"
                  + "\"5\":18446744073709551615,\"6\":18446744073709551615}"));
        }

        @Test @DisplayName("64- and 32-bit values are hex: their type is unknown")
        void fixedWidth() throws Exception {
            assertThat(raw(hex(out -> {
                out.writeDouble(1, 1.0);
                out.writeFloat(2, 1f);
                out.writeFixed64(3, -1L);
                out.writeFixed32(4, 0);
            }))).isEqualTo(json("{\"1\":\"0x3ff0000000000000\",\"2\":\"0x3f800000\","
                  + "\"3\":\"0xffffffffffffffff\",\"4\":\"0x00000000\"}"));
        }

        @Test @DisplayName("a length-delimited value is a message when it reads as one, else text, else base64")
        void lengthDelimited() throws Exception {
            assertThat(raw(hex(out -> {
                message(out, 1, inner -> inner.writeInt32(1, 7));
                out.writeString(2, "héllo 🌊");
                out.writeBytes(3, ByteString.copyFrom(new byte[]{(byte) 0xff, (byte) 0xfe, 0x00}));
                out.writeBytes(4, ByteString.EMPTY);
            }))).isEqualTo(json("{\"1\":{\"1\":7},\"2\":\"héllo 🌊\",\"3\":\"//4A\",\"4\":\"\"}"));
        }

        @Test @DisplayName("a field seen more than once is a list, in the order seen")
        void repeated() throws Exception {
            assertThat(raw(hex(out -> {
                out.writeInt32(2, 1);
                out.writeString(1, "a");
                out.writeInt32(2, 2);
                out.writeInt32(2, 3);
            }))).isEqualTo(json("{\"2\":[1,2,3],\"1\":\"a\"}"));
            assertThat(raw(hex(out -> {
                out.writeInt32(2, 1);
                out.writeString(1, "a");
            })).fieldNames()).toIterable().containsExactly("2", "1");
        }

        @Test @DisplayName("groups are objects, nested as written")
        void groups() throws Exception {
            assertThat(raw(hex(out -> {
                out.writeTag(1, WireFormat.WIRETYPE_START_GROUP);
                out.writeInt32(2, 5);
                out.writeTag(3, WireFormat.WIRETYPE_START_GROUP);
                out.writeString(4, "x");
                out.writeTag(3, WireFormat.WIRETYPE_END_GROUP);
                out.writeTag(1, WireFormat.WIRETYPE_END_GROUP);
                out.writeInt32(5, 6);
            }))).isEqualTo(json("{\"1\":{\"2\":5,\"3\":{\"4\":\"x\"}},\"5\":6}"));
        }

        @Test @DisplayName("a malformed payload is refused, naming the byte")
        void malformed() throws Exception {
            String[][] cases = {
                  {"08", "ends inside a varint at byte 1"},
                  {"0a 05 01", "holds a length of 5 bytes where 1 remain at byte 1"},
                  {"09 00 00", "ends inside a 64-bit value at byte 1"},
                  {"0d 00", "ends inside a 32-bit value at byte 1"},
                  {"0e 00", "has wire type 6, which protobuf does not define at byte 0"},
                  {"0f", "has wire type 7"},
                  {"00", "has a field numbered 0 at byte 0"},
                  {"08 ff ff ff ff ff ff ff ff ff ff 01", "holds a varint longer than 10 bytes at byte 1"},
                  {"0c", "ends group 1, which was never started at byte 0"},
                  {"0b 08 01", "ends before group 1 is closed"},
                  {"0b 14", "ends group 2, which was never started"},
            };
            for (String[] c : cases)
                assertThatThrownBy(() -> raw(c[0])).as(c[0])
                      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(c[1]);
            String pastLimit = hex(out -> out.writeUInt64NoTag(536_870_912L << 3));
            assertThatThrownBy(() -> raw(pastLimit)).hasMessageContaining("past 536,870,911");
            // The largest field number is fine.
            assertThat(raw(hex(out -> out.writeInt32(536_870_911, 1)))).isEqualTo(json("{\"536870911\":1}"));
        }

        @Test @DisplayName("groups nested past protobuf's limit are refused; messages past it read as bytes")
        void depth() throws Exception {
            int deep = ProtoPayloadDecoder.MAX_DEPTH + 1;
            String groups = hex(out -> {
                for (int i = 0; i < deep; i++) out.writeTag(1, WireFormat.WIRETYPE_START_GROUP);
                for (int i = 0; i < deep; i++) out.writeTag(1, WireFormat.WIRETYPE_END_GROUP);
            });
            assertThatThrownBy(() -> raw(groups)).hasMessageContaining("more than 100 deep");
            // A length-delimited value is only tried as a message, so nesting
            // past the limit ends in bytes rather than an error.
            Writes nested = out -> out.writeString(1, "leaf");
            for (int i = 0; i < 150; i++) {
                Writes inner = nested;
                nested = out -> message(out, 1, inner);
            }
            JsonNode tree = raw(hex(nested));
            int depth = 0;
            for (JsonNode node = tree; node.isObject(); node = node.get("1")) depth++;
            assertThat(depth).isBetween(100, 102);
        }

        @Test @DisplayName("random bytes decode or are refused as malformed; nothing else escapes")
        void randomBytes() {
            Random random = new Random(20261004);
            for (int i = 0; i < 20_000; i++) {
                byte[] bytes = new byte[1 + random.nextInt(47)];
                random.nextBytes(bytes);
                Throwable failure = catchThrowable(() -> raw(HexFormat.of().formatHex(bytes)));
                if (failure != null)
                    assertThat(failure).as(HexFormat.of().formatHex(bytes))
                          .isInstanceOf(ProtoPayloadDecoder.MalformedPayload.class);
            }
        }
    }

    @Nested @DisplayName("with a schema")
    class Typed {

        static final String SCHEMA = """
              syntax = "proto3";
              package demo.v1;

              import "google/protobuf/timestamp.proto";

              message Scalars {
                int32 i32 = 1;
                int64 i64 = 2;
                uint32 u32 = 3;
                uint64 u64 = 4;
                sint32 s32 = 5;
                sint64 s64 = 6;
                fixed32 f32 = 7;
                fixed64 f64 = 8;
                sfixed32 sf32 = 9;
                sfixed64 sf64 = 10;
                float fl = 11;
                double db = 12;
                bool b = 13;
                string s = 14;
                bytes by = 15;
              }

              message Outer {
                message Inner {
                  int32 v = 1;
                  Status status = 2;
                }
                enum Status {
                  STATUS_UNSPECIFIED = 0;
                  ACTIVE = 1;
                  RETIRED = 2;
                }
                string name = 1 [json_name = "displayName"];
                Inner inner = 2;
                repeated Inner list = 3;
                repeated int32 packed = 4;
                repeated int32 unpacked = 5 [packed = false];
                map<string, int64> counts = 6;
                map<int32, Inner> by_id = 7;
                map<bool, string> flags = 8;
                oneof choice {
                  string text = 9;
                  int32 number = 10;
                }
                Status status = 11;
                repeated Status statuses = 12;
                google.protobuf.Timestamp when = 13;
                Outer child = 14;
                .demo.v1.Outer.Inner absolute = 15;
              }

              enum Alias {
                option allow_alias = true;
                ZERO = 0;
                ONE = 1;
                UNO = 1;
              }

              message Aliased {
                Alias a = 1;
              }
              """;

        static final String PROTO2 = """
              syntax = "proto2";
              message Legacy {
                optional group Result = 1 {
                  optional string url = 2;
                }
                repeated group Item = 3 {
                  optional int32 id = 4;
                }
                optional int32 tail = 5;
              }
              """;

        @Test @DisplayName("every scalar type, at the edges of its range")
        void scalars() throws Exception {
            String payload = hex(out -> {
                out.writeInt32(1, -5);
                out.writeInt64(2, Long.MIN_VALUE);
                out.writeUInt32(3, -1);
                out.writeUInt64(4, -1L);
                out.writeSInt32(5, -3);
                out.writeSInt64(6, Long.MIN_VALUE);
                out.writeFixed32(7, -1);
                out.writeFixed64(8, -1L);
                out.writeSFixed32(9, -7);
                out.writeSFixed64(10, -9L);
                out.writeFloat(11, 0.1f);
                out.writeDouble(12, -0.0);
                out.writeBool(13, true);
                out.writeString(14, "héllo 🌊");
                out.writeBytes(15, ByteString.copyFrom(new byte[]{0x00, (byte) 0xff}));
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Scalars")).isEqualTo(json("{\"i32\":-5,"
                  + "\"i64\":-9223372036854775808,\"u32\":4294967295,\"u64\":18446744073709551615,\"s32\":-3,"
                  + "\"s64\":-9223372036854775808,\"f32\":4294967295,\"f64\":18446744073709551615,\"sf32\":-7,"
                  + "\"sf64\":-9,\"fl\":0.1,\"db\":-0.0,\"b\":true,\"s\":\"héllo 🌊\",\"by\":\"AP8=\"}"));
        }

        @Test @DisplayName("NaN and the infinities are strings, as proto3's JSON mapping writes them")
        void floatingSpecials() throws Exception {
            String payload = hex(out -> {
                out.writeFloat(11, Float.NaN);
                out.writeDouble(12, Double.NEGATIVE_INFINITY);
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Scalars")).isEqualTo(json("{\"fl\":\"NaN\",\"db\":\"-Infinity\"}"));
            assertThat(typed(hex(out -> out.writeFloat(11, Float.POSITIVE_INFINITY)), SCHEMA, "demo.v1.Scalars"))
                  .isEqualTo(json("{\"fl\":\"Infinity\"}"));
        }

        @Test @DisplayName("enums by name, an unknown value by number, an alias by its first name")
        void enums() throws Exception {
            assertThat(typed(hex(out -> {
                out.writeEnum(11, 2);
                out.writeEnum(12, 1);
                out.writeEnum(12, 7);
                out.writeEnum(12, 0);
            }), SCHEMA, "demo.v1.Outer")).isEqualTo(json(
                  "{\"status\":\"RETIRED\",\"statuses\":[\"ACTIVE\",7,\"STATUS_UNSPECIFIED\"]}"));
            assertThat(typed(hex(out -> out.writeEnum(1, 1)), SCHEMA, "demo.v1.Aliased"))
                  .isEqualTo(json("{\"a\":\"ONE\"}"));
        }

        @Test @DisplayName("messages, nested and repeated, by relative and absolute names; json_name for keys")
        void messages() throws Exception {
            String payload = hex(out -> {
                out.writeString(1, "outer");
                message(out, 2, inner -> {
                    inner.writeInt32(1, 1);
                    inner.writeEnum(2, 1);
                });
                message(out, 3, inner -> inner.writeInt32(1, 2));
                message(out, 3, inner -> inner.writeInt32(1, 3));
                message(out, 14, child -> {
                    child.writeString(1, "child");
                    message(child, 2, inner -> inner.writeInt32(1, 4));
                });
                message(out, 15, inner -> inner.writeInt32(1, 5));
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Outer")).isEqualTo(json("{\"displayName\":\"outer\","
                  + "\"inner\":{\"v\":1,\"status\":\"ACTIVE\"},\"list\":[{\"v\":2},{\"v\":3}],"
                  + "\"child\":{\"displayName\":\"child\",\"inner\":{\"v\":4}},\"absolute\":{\"v\":5}}"));
        }

        @Test @DisplayName("repeated scalars, packed or not, whatever the schema says")
        void packedAndUnpacked() throws Exception {
            Writes packedValues = out -> {
                int size = 0;
                for (int v : new int[]{1, -2, 300}) size += CodedOutputStream.computeInt32SizeNoTag(v);
                out.writeTag(4, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                out.writeUInt32NoTag(size);
                for (int v : new int[]{1, -2, 300}) out.writeInt32NoTag(v);
            };
            // Field 4 is packed and field 5 is not, but a parser takes either form for both.
            String payload = hex(out -> {
                packedValues.to(out);
                out.writeInt32(4, 4);
                out.writeInt32(5, 5);
                out.writeTag(5, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                out.writeUInt32NoTag(2);
                out.writeInt32NoTag(6);
                out.writeInt32NoTag(7);
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Outer"))
                  .isEqualTo(json("{\"packed\":[1,-2,300,4],\"unpacked\":[5,6,7]}"));
        }

        @Test @DisplayName("maps of scalars and messages, keyed as JSON keys, defaults for missing parts")
        void maps() throws Exception {
            String payload = hex(out -> {
                message(out, 6, entry -> {
                    entry.writeString(1, "a");
                    entry.writeInt64(2, 1);
                });
                message(out, 6, entry -> {
                    entry.writeString(1, "b");
                    entry.writeInt64(2, Long.MAX_VALUE);
                });
                message(out, 6, entry -> entry.writeString(1, "a"));       // a again, value absent: 0, last wins
                message(out, 6, entry -> entry.writeInt64(2, 9));          // key absent: ""
                message(out, 7, entry -> {
                    entry.writeInt32(1, -1);
                    message(entry, 2, inner -> inner.writeInt32(1, 8));
                });
                message(out, 8, entry -> {
                    entry.writeBool(1, true);
                    entry.writeString(2, "on");
                });
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Outer")).isEqualTo(json("{\"counts\":{\"a\":0,"
                  + "\"b\":9223372036854775807,\"\":9},\"by_id\":{\"-1\":{\"v\":8}},\"flags\":{\"true\":\"on\"}}"));
        }

        @Test @DisplayName("the oneof member that is set, and a message from an imported file decoded raw")
        void oneofAndImported() throws Exception {
            String payload = hex(out -> {
                out.writeInt32(10, 42);
                message(out, 13, timestamp -> {
                    timestamp.writeInt64(1, 1_700_000_000L);
                    timestamp.writeInt32(2, 5);
                });
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Outer"))
                  .isEqualTo(json("{\"number\":42,\"when\":{\"1\":1700000000,\"2\":5}}"));
        }

        @Test @DisplayName("fields the schema does not declare are kept under their numbers, after the rest")
        void unknownFields() throws Exception {
            JsonNode decoded = typed(hex(out -> {
                out.writeInt32(99, 1);
                out.writeString(1, "x");
                out.writeInt32(99, 2);
            }), SCHEMA, "demo.v1.Outer");
            assertThat(decoded).isEqualTo(json("{\"displayName\":\"x\",\"99\":[1,2]}"));
            assertThat(decoded.fieldNames()).toIterable().containsExactly("displayName", "99");
        }

        @Test @DisplayName("a singular field seen twice: a scalar's last value, a message's merge")
        void lastAndMerged() throws Exception {
            String payload = hex(out -> {
                out.writeString(1, "first");
                message(out, 2, inner -> inner.writeInt32(1, 1));
                out.writeString(1, "last");
                message(out, 2, inner -> inner.writeEnum(2, 2));
            });
            assertThat(typed(payload, SCHEMA, "demo.v1.Outer"))
                  .isEqualTo(json("{\"displayName\":\"last\",\"inner\":{\"v\":1,\"status\":\"RETIRED\"}}"));
        }

        @Test @DisplayName("proto2 groups, singular and repeated")
        void proto2Groups() throws Exception {
            String payload = hex(out -> {
                out.writeTag(1, WireFormat.WIRETYPE_START_GROUP);
                out.writeString(2, "https://example.com");
                out.writeTag(1, WireFormat.WIRETYPE_END_GROUP);
                out.writeTag(3, WireFormat.WIRETYPE_START_GROUP);
                out.writeInt32(4, 1);
                out.writeTag(3, WireFormat.WIRETYPE_END_GROUP);
                out.writeTag(3, WireFormat.WIRETYPE_START_GROUP);
                out.writeInt32(4, 2);
                out.writeTag(3, WireFormat.WIRETYPE_END_GROUP);
                out.writeInt32(5, 9);
            });
            assertThat(typed(payload, PROTO2, "Legacy")).isEqualTo(json(
                  "{\"result\":{\"url\":\"https://example.com\"},\"item\":[{\"id\":1},{\"id\":2}],\"tail\":9}"));
        }

        @Test @DisplayName("a wire type the schema contradicts names the field and asks about the type")
        void mismatch() throws Exception {
            assertThatThrownBy(() -> typed(hex(out -> out.writeInt32(1, 5)), SCHEMA, "demo.v1.Outer"))
                  .hasMessageContaining("Field name (number 1) of demo.v1.Outer is declared string, "
                        + "but the payload holds a varint for it at byte 0")
                  .hasMessageContaining("Is demo.v1.Outer the right message type?");
            assertThatThrownBy(() -> typed(hex(out -> out.writeInt32(2, 5)), SCHEMA, "demo.v1.Outer"))
                  .hasMessageContaining("declared a message demo.v1.Outer.Inner");
            assertThatThrownBy(() -> typed(hex(out -> out.writeString(11, "x")), SCHEMA, "demo.v1.Outer"))
                  .hasMessageContaining("declared an enum demo.v1.Outer.Status");
        }

        @Test @DisplayName("a string field holding bytes that are not UTF-8 is refused")
        void invalidUtf8() throws Exception {
            String payload = hex(out -> out.writeBytes(1, ByteString.copyFrom(new byte[]{(byte) 0xc3, 0x28})));
            assertThatThrownBy(() -> typed(payload, SCHEMA, "demo.v1.Outer"))
                  .hasMessageContaining("Field name (number 1) is a string, but its bytes at byte 2 are not UTF-8");
        }

        @Test @DisplayName("an unknown message type is refused, listing the schema's")
        void unknownMessage() {
            assertThatThrownBy(() -> typed("08 01", SCHEMA, "demo.v1.Nope"))
                  .hasMessageContaining("The schema declares no message demo.v1.Nope")
                  .hasMessageContaining("demo.v1.Scalars, demo.v1.Outer, demo.v1.Outer.Inner");
        }

        @Test @DisplayName("a schema that does not parse is refused, saying why")
        void brokenSchema() {
            assertThatThrownBy(() -> typed("08 01", "message A { int32 a = 1;", "A"))
                  .hasMessageContaining("Unbalanced braces");
        }

        @Test @DisplayName("messages nested past protobuf's limit are refused")
        void depth() throws Exception {
            Writes nested = out -> out.writeString(1, "leaf");
            for (int i = 0; i <= ProtoPayloadDecoder.MAX_DEPTH; i++) {
                Writes inner = nested;
                nested = out -> message(out, 14, inner);
            }
            String payload = hex(nested);
            assertThatThrownBy(() -> typed(payload, SCHEMA, "demo.v1.Outer")).hasMessageContaining("more than 100 deep");
        }

        @Test @DisplayName("the same text decodes the same way, schema read once")
        void schemaReuse() throws Exception {
            String payload = hex(out -> out.writeString(1, "x"));
            for (int i = 0; i < 3; i++)
                assertThat(typed(payload, SCHEMA, "demo.v1.Outer")).isEqualTo(json("{\"displayName\":\"x\"}"));
            assertThat(typed(payload, SCHEMA.replace("displayName", "title"), "demo.v1.Outer"))
                  .isEqualTo(json("{\"title\":\"x\"}"));
        }
    }

    @Nested @DisplayName("read as protobuf parses it")
    class AsProtobufParses {

        /** Every way a field can hold its default, in each of protobuf's syntaxes. */
        static String presence(String header) {
            return header + """

                  message P {
                    int32 plain = 1;
                    optional int32 marked = 2;
                    oneof choice {
                      int32 member = 3;
                      string other = 4;
                    }
                    double real = 5;
                    Kind kind = 6;
                    string text = 7;
                    bool flag = 8;
                    bytes data = 9;
                    repeated int32 list = 10;
                    P child = 11;
                  }
                  enum Kind {
                    KIND_UNSPECIFIED = 0;
                    KIND_SET = 1;
                  }
                  """;
        }

        /** Every field of P set to its default: 0, "", false, empty bytes, the zero enum, an empty message. */
        static final Writes DEFAULTS = out -> {
            out.writeInt32(1, 0);
            out.writeInt32(2, 0);
            out.writeInt32(3, 0);
            out.writeDouble(5, 0.0);
            out.writeEnum(6, 0);
            out.writeString(7, "");
            out.writeBool(8, false);
            out.writeBytes(9, ByteString.EMPTY);
            out.writeInt32(10, 0);
            message(out, 11, child -> {});
        };

        @Test @DisplayName("proto3 leaves out a field without presence that holds its default, as its JSON mapping does")
        void proto3Defaults() throws Exception {
            assertThat(typed(hex(DEFAULTS), presence("syntax = \"proto3\";"), "P"))
                  .isEqualTo(json("{\"marked\":0,\"member\":0,\"list\":[0],\"child\":{}}"));
        }

        @Test @DisplayName("proto2 keeps every field the payload sets, defaults included")
        void proto2Defaults() throws Exception {
            // Every singular field labelled optional, as proto2 needs; a oneof's members take no label.
            String proto2 = presence("syntax = \"proto2\";").replaceAll("\n {2}(int32|double|Kind|string|bool|bytes|P) ",
                  "\n  optional $1 ");
            assertThat(typed(hex(DEFAULTS), proto2, "P")).isEqualTo(json("{\"plain\":0,\"marked\":0,\"member\":0,"
                  + "\"real\":0.0,\"kind\":\"KIND_UNSPECIFIED\",\"text\":\"\",\"flag\":false,\"data\":\"\","
                  + "\"list\":[0],\"child\":{}}"));
            // A file without a syntax statement is proto2.
            assertThat(typed(hex(out -> out.writeInt32(1, 0)), proto2.replace("syntax = \"proto2\";", ""), "P"))
                  .isEqualTo(json("{\"plain\":0}"));
        }

        @Test @DisplayName("editions: presence is explicit unless the file or the field makes it implicit")
        void editions() throws Exception {
            String explicit = presence("edition = \"2023\";").replace("optional int32 marked = 2;",
                  "int32 marked = 2 [features.field_presence = IMPLICIT];");
            assertThat(typed(hex(out -> {
                out.writeInt32(1, 0);
                out.writeInt32(2, 0);
            }), explicit, "P")).isEqualTo(json("{\"plain\":0}"));
            String implicit = presence("edition = \"2023\";\noption features.field_presence = IMPLICIT;")
                  .replace("optional int32 marked = 2;", "int32 marked = 2 [features.field_presence = EXPLICIT];");
            assertThat(typed(hex(out -> {
                out.writeInt32(1, 0);
                out.writeInt32(2, 0);
            }), implicit, "P")).isEqualTo(json("{\"marked\":0}"));
        }

        @Test @DisplayName("-0.0 is not the default, and a field set back to its default is left out")
        void negativeZeroAndLastValue() throws Exception {
            String proto3 = presence("syntax = \"proto3\";");
            assertThat(typed(hex(out -> out.writeDouble(5, -0.0)), proto3, "P")).isEqualTo(json("{\"real\":-0.0}"));
            assertThat(typed(hex(out -> {
                out.writeInt32(1, 7);
                out.writeEnum(6, 1);
                out.writeInt32(1, 0);
                out.writeEnum(6, 0);
            }), proto3, "P")).isEqualTo(json("{}"));
        }

        @Test @DisplayName("of a oneof's members, only the one set last; set again after another, it starts over")
        void oneofLastMember() throws Exception {
            String proto3 = presence("syntax = \"proto3\";").replace("string other = 4;", "P other = 4;");
            assertThat(typed(hex(out -> {
                out.writeInt32(3, 5);
                message(out, 4, other -> other.writeInt32(1, 1));
            }), proto3, "P")).isEqualTo(json("{\"other\":{\"plain\":1}}"));
            assertThat(typed(hex(out -> {
                message(out, 4, other -> other.writeInt32(1, 1));
                out.writeInt32(3, 5);
                message(out, 4, other -> other.writeEnum(6, 1));
            }), proto3, "P")).isEqualTo(json("{\"other\":{\"kind\":\"KIND_SET\"}}"));
            assertThat(typed(hex(out -> {
                message(out, 4, other -> other.writeInt32(1, 1));
                message(out, 4, other -> other.writeEnum(6, 1));
            }), proto3, "P")).isEqualTo(json("{\"other\":{\"plain\":1,\"kind\":\"KIND_SET\"}}"));
        }

        @Test @DisplayName("a singular group set twice is merged, as a message is; a group declares oneofs and types")
        void groupsMerge() throws Exception {
            String schema = """
                  syntax = "proto2";
                  message Outer {
                    optional group Result = 1 {
                      repeated int32 ids = 2;
                      optional string url = 3;
                      oneof kind {
                        int32 code = 4;
                        Detail detail = 5;
                      }
                      message Detail {
                        optional string why = 6;
                      }
                    }
                  }
                  """;
            String payload = hex(out -> {
                out.writeTag(1, WireFormat.WIRETYPE_START_GROUP);
                out.writeInt32(2, 1);
                out.writeString(3, "first");
                out.writeInt32(4, 9);
                out.writeTag(1, WireFormat.WIRETYPE_END_GROUP);
                out.writeTag(1, WireFormat.WIRETYPE_START_GROUP);
                out.writeInt32(2, 2);
                message(out, 5, detail -> detail.writeString(6, "because"));
                out.writeTag(1, WireFormat.WIRETYPE_END_GROUP);
            });
            assertThat(typed(payload, schema, "Outer")).isEqualTo(json(
                  "{\"result\":{\"ids\":[1,2],\"url\":\"first\",\"detail\":{\"why\":\"because\"}}}"));
            assertThat(new ProtoConverter().readSchema(schema).messageNames())
                  .containsExactly("Outer", "Outer.Result", "Outer.Result.Detail");
        }

        @Test @DisplayName("in one map entry, a message value set twice is merged and a key set twice is the last")
        void mapEntryMerges() throws Exception {
            String schema = """
                  syntax = "proto3";
                  message M {
                    map<string, V> m = 1;
                  }
                  message V {
                    int32 x = 1;
                    int32 y = 2;
                    repeated int32 z = 3;
                  }
                  """;
            String payload = hex(out -> {
                message(out, 1, entry -> {
                    entry.writeString(1, "a");
                    message(entry, 2, v -> {
                        v.writeInt32(1, 1);
                        v.writeInt32(3, 1);
                    });
                    message(entry, 2, v -> {
                        v.writeInt32(2, 2);
                        v.writeInt32(3, 2);
                    });
                });
                message(out, 1, entry -> {
                    entry.writeString(1, "b");
                    message(entry, 2, v -> v.writeInt32(1, 4));
                    entry.writeString(1, "c");
                });
            });
            String expected = "{\"m\":{\"a\":{\"x\":1,\"y\":2,\"z\":[1,2]},\"c\":{\"x\":4}}}";
            assertThat(typed(payload, schema, "M")).isEqualTo(json(expected));

            // As protobuf-java parses the same bytes, from a descriptor built by hand.
            DescriptorProtos.FieldDescriptorProto.Builder value = DescriptorProtos.FieldDescriptorProto.newBuilder()
                  .setName("value").setNumber(2).setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                  .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".V");
            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                  .setName("m.proto").setSyntax("proto3")
                  .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("M")
                        .addNestedType(DescriptorProtos.DescriptorProto.newBuilder().setName("MEntry")
                              .setOptions(DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true))
                              .addField(scalarField("key", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING, false))
                              .addField(value))
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder().setName("m").setNumber(1)
                              .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                              .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".M.MEntry")))
                  .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("V")
                        .addField(scalarField("x", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, false))
                        .addField(scalarField("y", 2, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, false))
                        .addField(scalarField("z", 3, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, true)))
                  .build();
            Descriptors.Descriptor m = Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0])
                  .findMessageTypeByName("M");
            assertThat(json(JsonFormat.printer().preservingProtoFieldNames()
                  .print(DynamicMessage.parseFrom(m, HexFormat.of().parseHex(payload))))).isEqualTo(json(expected));
        }

        private static DescriptorProtos.FieldDescriptorProto.Builder scalarField(String name, int number,
              DescriptorProtos.FieldDescriptorProto.Type type, boolean repeated) {
            return DescriptorProtos.FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                  .setLabel(repeated ? DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED
                        : DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL);
        }

        @Test @DisplayName("an error in a message set twice names its byte in the payload, not in the merge")
        void mergedErrorPosition() throws Exception {
            // inner {v: 1}, then inner {v as a length-delimited value}: byte 6 holds the wrong tag.
            assertThatThrownBy(() -> typed("12 02 08 01 12 02 0a 00", Typed.SCHEMA, "demo.v1.Outer"))
                  .hasMessageContaining("Field v (number 1) of demo.v1.Outer.Inner is declared int32, "
                        + "but the payload holds a length-delimited value for it at byte 6.");
            // Merged twice over: the error is still where the payload has it.
            assertThatThrownBy(() -> typed("72 04 12 02 08 01 72 06 12 04 08 01 10 ff", Typed.SCHEMA, "demo.v1.Outer"))
                  .hasMessageContaining("The payload ends inside a varint at byte 13.");
        }

        @Test @DisplayName("a group's end is checked even when its fields are read later")
        void groupEnd() {
            String schema = "syntax = \"proto2\";\nmessage Outer {\n  optional group Result = 1 {\n"
                  + "    optional int32 id = 2;\n  }\n}\n";
            assertThatThrownBy(() -> typed("0b 10 01", schema, "Outer")).hasMessageContaining("ends before group 1 is closed");
            assertThatThrownBy(() -> typed("0b 10 01 14", schema, "Outer"))
                  .hasMessageContaining("ends group 2, which was never started at byte 3");
        }

        @Test @DisplayName("a syntax statement without a quoted value is refused")
        void syntaxWithoutValue() {
            assertThatThrownBy(() -> typed("08 01", "syntax = ;\nmessage A { int32 a = 1; }", "A"))
                  .hasMessageContaining("The syntax statement needs a quoted value");
        }
    }
}
