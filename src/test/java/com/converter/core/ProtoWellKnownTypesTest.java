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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Well-known types and imported files, decoded and checked against
 * protobuf-java: its own Timestamp, Duration, wrappers, Struct, Value,
 * ListValue, Empty, FieldMask and Any, printed by its JsonFormat.
 */
@DisplayName("Protobuf well-known types and imports")
class ProtoWellKnownTypesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private final ProtoPayloadDecoder decoder = new ProtoPayloadDecoder(new ProtoConverter());

    private static final String SCHEMA = """
          syntax = "proto3";
          package wkt;

          import "google/protobuf/timestamp.proto";
          import "google/protobuf/duration.proto";
          import "google/protobuf/wrappers.proto";
          import "google/protobuf/struct.proto";
          import "google/protobuf/empty.proto";
          import "google/protobuf/field_mask.proto";
          import "google/protobuf/any.proto";

          message M {
            google.protobuf.Timestamp t = 1;
            google.protobuf.Duration d = 2;
            google.protobuf.DoubleValue dv = 3;
            google.protobuf.FloatValue fv = 4;
            google.protobuf.Int64Value i64 = 5;
            google.protobuf.UInt64Value u64 = 6;
            google.protobuf.Int32Value i32 = 7;
            google.protobuf.UInt32Value u32 = 8;
            google.protobuf.BoolValue b = 9;
            google.protobuf.StringValue s = 10;
            google.protobuf.BytesValue by = 11;
            google.protobuf.Struct st = 12;
            google.protobuf.Value v = 13;
            google.protobuf.ListValue lv = 14;
            google.protobuf.Empty e = 15;
            google.protobuf.FieldMask fm = 16;
            google.protobuf.Any a = 17;
            repeated google.protobuf.Timestamp ts = 18;
            map<string, google.protobuf.Value> mv = 19;
            google.protobuf.NullValue nv = 20;
            Inner inner = 21;

            message Inner {
              int32 x = 1;
              string name = 2;
            }
          }
          """;

    /** {@link #SCHEMA}, as protobuf-java builds it: on its own well-known types' files. */
    private static Descriptor descriptor() throws Exception {
        String[] wellKnown = {"Timestamp", "Duration", "DoubleValue", "FloatValue", "Int64Value", "UInt64Value",
              "Int32Value", "UInt32Value", "BoolValue", "StringValue", "BytesValue", "Struct", "Value", "ListValue",
              "Empty", "FieldMask", "Any"};
        DescriptorProto.Builder m = DescriptorProto.newBuilder().setName("M");
        for (int i = 0; i < wellKnown.length; i++)
            m.addField(message(new String[]{"t", "d", "dv", "fv", "i64", "u64", "i32", "u32", "b", "s", "by", "st",
                  "v", "lv", "e", "fm", "a"}[i], i + 1, ".google.protobuf." + wellKnown[i], false));
        m.addField(message("ts", 18, ".google.protobuf.Timestamp", true));
        m.addField(message("mv", 19, ".wkt.M.MvEntry", true));
        m.addField(FieldDescriptorProto.newBuilder().setName("nv").setNumber(20)
              .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_ENUM)
              .setTypeName(".google.protobuf.NullValue"));
        m.addField(message("inner", 21, ".wkt.M.Inner", false));
        m.addNestedType(DescriptorProto.newBuilder().setName("MvEntry")
              .setOptions(MessageOptions.newBuilder().setMapEntry(true))
              .addField(FieldDescriptorProto.newBuilder().setName("key").setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_STRING))
              .addField(message("value", 2, ".google.protobuf.Value", false)));
        m.addNestedType(DescriptorProto.newBuilder().setName("Inner")
              .addField(FieldDescriptorProto.newBuilder().setName("x").setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_INT32))
              .addField(FieldDescriptorProto.newBuilder().setName("name").setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_STRING)));
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("wkt.proto").setPackage("wkt")
              .setSyntax("proto3")
              .addAllDependency(List.of("google/protobuf/timestamp.proto", "google/protobuf/duration.proto",
                    "google/protobuf/wrappers.proto", "google/protobuf/struct.proto", "google/protobuf/empty.proto",
                    "google/protobuf/field_mask.proto", "google/protobuf/any.proto"))
              .addMessageType(m).build();
        FileDescriptor built = FileDescriptor.buildFrom(file, new FileDescriptor[]{
              Timestamp.getDescriptor().getFile(), Duration.getDescriptor().getFile(),
              DoubleValue.getDescriptor().getFile(), Struct.getDescriptor().getFile(), Empty.getDescriptor().getFile(),
              FieldMask.getDescriptor().getFile(), Any.getDescriptor().getFile()});
        return built.findMessageTypeByName("M");
    }

    private static FieldDescriptorProto.Builder message(String name, int number, String type, boolean repeated) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setTypeName(type)
              .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
              .setLabel(repeated ? FieldDescriptorProto.Label.LABEL_REPEATED : FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    @Test @DisplayName("random well-known values decode as protobuf-java's JsonFormat prints them")
    void wellKnownTypesAgreeWithJsonFormat() throws Exception {
        Descriptor type = descriptor();
        Descriptor inner = type.findNestedTypeByName("Inner");
        JsonFormat.Printer printer = JsonFormat.printer().preservingProtoFieldNames()
              .usingTypeRegistry(TypeRegistry.newBuilder().add(type).add(inner).add(Timestamp.getDescriptor()).build());
        Random random = new Random(20261019);
        for (int round = 0; round < 2_000; round++) {
            DynamicMessage.Builder message = DynamicMessage.newBuilder(type);
            for (FieldDescriptor field : type.getFields())
                if (random.nextInt(3) > 0) set(message, field, random, inner, MASKS);
            byte[] wire = message.build().toByteArray();
            if (wire.length == 0) continue;
            JsonNode expected = exactWrappedLongs(JSON.readTree(printer.print(message.build())));
            String actual = decoder.decode(HEX.formatHex(wire), SCHEMA, "wkt.M");
            assertThat(JSON.readTree(actual)).as("%s%n%s", HEX.formatHex(wire), message.build()).isEqualTo(expected);
        }
    }

    /** JsonFormat writes an Int64Value or UInt64Value as a string; the decoder, as every 64-bit integer, as a number. */
    private static JsonNode exactWrappedLongs(JsonNode node) throws Exception {
        ObjectNode out = node.deepCopy();
        for (String key : List.of("i64", "u64")) if (out.has(key)) out.set(key, JSON.readTree(out.get(key).asText()));
        return out;
    }

    /** FieldMask paths; with_2_digits is written with2Digits, which reads back as with2_digits, in protobuf too. */
    private static final List<String> MASKS = List.of("a", "foo_bar", "foo_bar.baz_qux", "x.y_z", "with_2_digits");
    private static final List<String> ROUND_TRIP_MASKS = MASKS.subList(0, 4);

    private static void set(DynamicMessage.Builder message, FieldDescriptor field, Random random, Descriptor inner,
          List<String> masks) {
        switch (field.getName()) {
            case "t" -> message.setField(field, timestamp(random));
            case "d" -> {
                long seconds = random.nextLong(-315_576_000_000L, 315_576_000_001L);
                int nanos = nanos(random);
                if (seconds < 0) nanos = -nanos;
                if (seconds == 0 && random.nextBoolean()) nanos = -nanos;
                message.setField(field, Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build());
            }
            case "dv" -> message.setField(field, DoubleValue.of(random.nextInt(4) == 0 ? 0 : random.nextGaussian() * 1e6));
            case "fv" -> message.setField(field, FloatValue.of(random.nextInt(4) == 0 ? 0 : (float) random.nextGaussian()));
            case "i64" -> message.setField(field, Int64Value.of(random.nextInt(4) == 0 ? 0 : random.nextLong()));
            case "u64" -> message.setField(field, UInt64Value.of(random.nextLong()));
            case "i32" -> message.setField(field, Int32Value.of(random.nextInt()));
            case "u32" -> message.setField(field, UInt32Value.of(random.nextInt()));
            case "b" -> message.setField(field, BoolValue.of(random.nextBoolean()));
            case "s" -> message.setField(field, StringValue.of(random.nextBoolean() ? "" : "text " + random.nextInt()));
            case "by" -> message.setField(field, BytesValue.of(ByteString.copyFrom(bytes(random))));
            case "st" -> message.setField(field, struct(random, 0));
            case "v" -> message.setField(field, value(random, 0));
            case "lv" -> message.setField(field, list(random, 0));
            case "e" -> message.setField(field, Empty.getDefaultInstance());
            case "fm" -> {
                FieldMask.Builder mask = FieldMask.newBuilder();
                for (int i = random.nextInt(4); i > 0; i--)
                    mask.addPaths(masks.get(random.nextInt(masks.size())));
                message.setField(field, mask.build());
            }
            case "a" -> {
                Message held = switch (random.nextInt(3)) {
                    case 0 -> DynamicMessage.newBuilder(inner).setField(inner.findFieldByName("x"), random.nextInt())
                          .setField(inner.findFieldByName("name"), "n" + random.nextInt(9)).build();
                    case 1 -> timestamp(random);
                    default -> DynamicMessage.getDefaultInstance(inner);
                };
                message.setField(field, Any.pack(held));
            }
            case "ts" -> {
                for (int i = random.nextInt(3); i > 0; i--) message.addRepeatedField(field, timestamp(random));
            }
            case "mv" -> {
                Descriptor entry = field.getMessageType();
                for (int i = random.nextInt(3); i > 0; i--)
                    message.addRepeatedField(field, DynamicMessage.newBuilder(entry)
                          .setField(entry.findFieldByName("key"), "k" + random.nextInt(5))
                          .setField(entry.findFieldByName("value"), value(random, 1)).build());
            }
            case "nv" -> message.setField(field, NullValue.NULL_VALUE.getValueDescriptor());
            case "inner" -> message.setField(field, DynamicMessage.newBuilder(inner)
                  .setField(inner.findFieldByName("x"), random.nextInt(100)).build());
            default -> throw new IllegalStateException(field.getName());
        }
    }

    private static Timestamp timestamp(Random random) {
        return Timestamp.newBuilder().setSeconds(random.nextLong(-62_135_596_800L, 253_402_300_800L))
              .setNanos(nanos(random)).build();
    }

    /** None, or a whole millisecond, microsecond or nanosecond, each of which is written differently. */
    private static int nanos(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> 0;
            case 1 -> random.nextInt(1_000) * 1_000_000;
            case 2 -> random.nextInt(1_000_000) * 1_000;
            default -> random.nextInt(1_000_000_000);
        };
    }

    private static byte[] bytes(Random random) {
        byte[] bytes = new byte[random.nextInt(6)];
        random.nextBytes(bytes);
        return bytes;
    }

    private static Value value(Random random, int depth) {
        return switch (random.nextInt(depth > 2 ? 4 : 6)) {
            case 0 -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
            case 1 -> Value.newBuilder().setNumberValue(random.nextInt(3) == 0 ? 0 : random.nextGaussian()).build();
            case 2 -> Value.newBuilder().setStringValue(random.nextBoolean() ? "" : "s" + random.nextInt()).build();
            case 3 -> Value.newBuilder().setBoolValue(random.nextBoolean()).build();
            case 4 -> Value.newBuilder().setStructValue(struct(random, depth + 1)).build();
            default -> Value.newBuilder().setListValue(list(random, depth + 1)).build();
        };
    }

    private static Struct struct(Random random, int depth) {
        Struct.Builder struct = Struct.newBuilder();
        for (int i = random.nextInt(3); i > 0; i--) struct.putFields("f" + random.nextInt(9), value(random, depth + 1));
        return struct.build();
    }

    private static ListValue list(Random random, int depth) {
        ListValue.Builder list = ListValue.newBuilder();
        for (int i = random.nextInt(3); i > 0; i--) list.addValues(value(random, depth + 1));
        return list.build();
    }

    @Test @DisplayName("JsonFormat's JSON of random well-known values encodes to what protobuf-java reads back")
    void wellKnownTypesEncode() throws Exception {
        Descriptor type = descriptor();
        Descriptor inner = type.findNestedTypeByName("Inner");
        JsonFormat.Printer printer = JsonFormat.printer().preservingProtoFieldNames()
              .usingTypeRegistry(TypeRegistry.newBuilder().add(type).add(inner).add(Timestamp.getDescriptor()).build());
        ProtoPayloadEncoder encoder = new ProtoPayloadEncoder(new ProtoConverter());
        Random random = new Random(1019);
        for (int round = 0; round < 2_000; round++) {
            DynamicMessage.Builder message = DynamicMessage.newBuilder(type);
            for (FieldDescriptor field : type.getFields())
                if (random.nextInt(3) > 0) set(message, field, random, inner, ROUND_TRIP_MASKS);
            // A map key written twice is printed twice: the last wins, as protobuf reads it.
            JsonNode json = JSON.readTree(printer.print(message.build()));
            String payload = encoder.encode(json.toString(), SCHEMA, "wkt.M", Map.of(), false);
            DynamicMessage back = DynamicMessage.parseFrom(type, HEX.parseHex(payload));
            assertThat(JSON.readTree(printer.print(back))).as(json.toString()).isEqualTo(json);
        }
    }

    @Test @DisplayName("an absent well-known value in a map, and an Any of a type the schema lacks")
    void defaultsAndUnknownAny() throws Exception {
        // mv { key: "k" } with no value: protobuf reads a default Value, which is null.
        String hex = HEX.formatHex(new byte[]{(byte) 0x9A, 0x01, 0x03, 0x0A, 0x01, 'k'});
        assertThat(decoder.decode(hex, SCHEMA, "wkt.M")).isEqualTo("{\"mv\":{\"k\":null}}");
        Any unknown = Any.newBuilder().setTypeUrl("type.googleapis.com/other.Thing")
              .setValue(ByteString.copyFrom(new byte[]{8, 1})).build();
        Descriptor type = descriptor();
        byte[] wire = DynamicMessage.newBuilder(type).setField(type.findFieldByName("a"), unknown).build().toByteArray();
        assertThat(decoder.decode(HEX.formatHex(wire), SCHEMA, "wkt.M"))
              .isEqualTo("{\"a\":{\"@type\":\"type.googleapis.com/other.Thing\",\"value\":\"CAE=\"}}");
    }

    @Test @DisplayName("a Timestamp or Duration protobuf cannot write is refused, as its printer refuses it")
    void outOfRange() {
        assertThatThrownBy(() -> WellKnownTypes.timestamp(253_402_300_800L, 0)).hasMessageContaining("years 1 and 9999");
        assertThatThrownBy(() -> WellKnownTypes.duration(1, -1)).hasMessageContaining("same sign");
        assertThat(WellKnownTypes.duration(0, -500_000_000)).isEqualTo("-0.500s");
        assertThat(WellKnownTypes.timestamp(0, 1_000)).isEqualTo("1970-01-01T00:00:00.000001Z");
    }

    // ── Imports ───────────────────────────────────────────────────────────

    private static final String MONEY = """
          syntax = "proto3";
          package shop;
          message Money {
            string currency = 1;
            int64 units = 2;
          }
          enum Status {
            UNKNOWN = 0;
            PAID = 1;
          }
          """;

    private static final String ORDER = """
          syntax = "proto3";
          package shop.v1;
          import "shop/money.proto";
          import "google/protobuf/timestamp.proto";
          message Order {
            shop.Money total = 1;
            Status status = 2;
            repeated .shop.Money items = 3;
            map<string, Money> by_name = 4;
            google.protobuf.Timestamp at = 5;
          }
          """;

    @Test @DisplayName("a type from an imported file is read with it, and decodes as protobuf-java decodes it")
    void importedTypes() throws Exception {
        ProtoSchema schema = new ProtoConverter().readSchema(ORDER, Map.of("shop/money.proto", MONEY));
        ProtoSchema.Message order = schema.messages().get("shop.v1.Order");
        assertThat(order.fields().get(1).kind()).isEqualTo(ProtoSchema.Kind.MESSAGE);
        assertThat(order.fields().get(1).type()).isEqualTo("shop.Money");
        assertThat(order.fields().get(2).kind()).isEqualTo(ProtoSchema.Kind.ENUM);
        assertThat(order.fields().get(2).implicitPresence()).isTrue();
        assertThat(order.fields().get(4).mapValue().type()).isEqualTo("shop.Money");
        assertThat(schema.messageNames()).containsExactly("shop.v1.Order", "shop.Money");

        FileDescriptor money = FileDescriptor.buildFrom(FileDescriptorProto.newBuilder().setName("shop/money.proto")
              .setPackage("shop").setSyntax("proto3")
              .addMessageType(DescriptorProto.newBuilder().setName("Money")
                    .addField(FieldDescriptorProto.newBuilder().setName("currency").setNumber(1)
                          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_STRING))
                    .addField(FieldDescriptorProto.newBuilder().setName("units").setNumber(2)
                          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_INT64)))
              .addEnumType(EnumDescriptorProto.newBuilder().setName("Status")
                    .addValue(EnumValueDescriptorProto.newBuilder().setName("UNKNOWN").setNumber(0))
                    .addValue(EnumValueDescriptorProto.newBuilder().setName("PAID").setNumber(1)))
              .build(), new FileDescriptor[0]);
        FileDescriptor orderFile = FileDescriptor.buildFrom(FileDescriptorProto.newBuilder().setName("shop/v1/order.proto")
              .setPackage("shop.v1").setSyntax("proto3").addDependency("shop/money.proto")
              .addDependency("google/protobuf/timestamp.proto")
              .addMessageType(DescriptorProto.newBuilder().setName("Order")
                    .addField(message("total", 1, ".shop.Money", false))
                    .addField(FieldDescriptorProto.newBuilder().setName("status").setNumber(2)
                          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_ENUM)
                          .setTypeName(".shop.Status"))
                    .addField(message("items", 3, ".shop.Money", true))
                    .addField(message("by_name", 4, ".shop.v1.Order.ByNameEntry", true))
                    .addField(message("at", 5, ".google.protobuf.Timestamp", false))
                    .addNestedType(DescriptorProto.newBuilder().setName("ByNameEntry")
                          .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                          .addField(FieldDescriptorProto.newBuilder().setName("key").setNumber(1)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(FieldDescriptorProto.Type.TYPE_STRING))
                          .addField(message("value", 2, ".shop.Money", false))))
              .build(), new FileDescriptor[]{money, Timestamp.getDescriptor().getFile()});
        Descriptor type = orderFile.findMessageTypeByName("Order");
        Descriptor moneyType = money.findMessageTypeByName("Money");
        Descriptor entry = type.findNestedTypeByName("ByNameEntry");
        DynamicMessage cash = DynamicMessage.newBuilder(moneyType).setField(moneyType.findFieldByName("currency"), "EUR")
              .setField(moneyType.findFieldByName("units"), 12L).build();
        DynamicMessage built = DynamicMessage.newBuilder(type)
              .setField(type.findFieldByName("total"), cash)
              .setField(type.findFieldByName("status"), money.findEnumTypeByName("Status").findValueByName("PAID"))
              .addRepeatedField(type.findFieldByName("items"), cash)
              .addRepeatedField(type.findFieldByName("by_name"), DynamicMessage.newBuilder(entry)
                    .setField(entry.findFieldByName("key"), "a").setField(entry.findFieldByName("value"), cash).build())
              .setField(type.findFieldByName("at"), Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
              .build();
        String expected = JsonFormat.printer().preservingProtoFieldNames().print(built).replace("\"12\"", "12");
        String actual = new ProtoPayloadDecoder(new ProtoConverter()).decode(HEX.formatHex(built.toByteArray()), ORDER,
              "shop.v1.Order", Map.of("shop/money.proto", MONEY));
        assertThat(JSON.readTree(actual)).isEqualTo(JSON.readTree(expected));
    }

    @Test @DisplayName("an import not at hand leaves its types to be decoded raw, as before")
    void missingImport() throws Exception {
        ProtoSchema schema = new ProtoConverter().readSchema(ORDER);
        assertThat(schema.messages().get("shop.v1.Order").fields().get(1).kind()).isEqualTo(ProtoSchema.Kind.UNRESOLVED);
        // The well-known one is supplied all the same.
        assertThat(schema.messages().get("shop.v1.Order").fields().get(5).kind()).isEqualTo(ProtoSchema.Kind.MESSAGE);
        assertThat(schema.messageNames()).containsExactly("shop.v1.Order");
    }

    @Test @DisplayName("imports are followed through the files that import, and a file of only enums is read")
    void transitiveImports() {
        String enums = "syntax = \"proto3\";\npackage base;\nenum Level { LOW = 0; HIGH = 1; }\n";
        String middle = "syntax = \"proto3\";\npackage mid;\nimport public \"base/level.proto\";\n"
              + "message Box { base.Level level = 1; }\n";
        String top = "syntax = \"proto3\";\npackage top;\nimport \"mid/box.proto\";\n"
              + "// import \"commented/out.proto\";\nmessage Top { mid.Box box = 1; base.Level level = 2; }\n";
        ProtoSchema schema = new ProtoConverter().readSchema(top,
              Map.of("mid/box.proto", middle, "base/level.proto", enums));
        assertThat(schema.messages().get("top.Top").fields().get(1).type()).isEqualTo("mid.Box");
        assertThat(schema.messages().get("top.Top").fields().get(2).kind()).isEqualTo(ProtoSchema.Kind.ENUM);
        assertThat(schema.messages().get("mid.Box").fields().get(1).kind()).isEqualTo(ProtoSchema.Kind.ENUM);
        assertThat(new ProtoConverter().importPaths(top)).containsExactly("mid/box.proto");
        assertThatThrownBy(() -> new ProtoConverter().readSchema(top, Map.of("mid/box.proto", "message {")))
              .hasMessageContaining("The imported file mid/box.proto could not be read");
    }
}
