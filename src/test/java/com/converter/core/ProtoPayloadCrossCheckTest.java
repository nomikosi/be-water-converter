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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.Edition;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumOptions;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FeatureSet;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.WireFormat;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoder checked against Google's protobuf-java: random schemas, built
 * both as {@code .proto} text for the decoder and as descriptors for
 * protobuf-java, with random messages that protobuf-java encodes, parses back
 * and prints with its own {@link JsonFormat}; and random, mutated wire data
 * that protobuf-java's own parser reads or refuses.
 */
@DisplayName("Protobuf payload decoding, checked against protobuf-java")
class ProtoPayloadCrossCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private final ProtoPayloadDecoder decoder = new ProtoPayloadDecoder(new ProtoConverter());

    enum Syntax { PROTO2, PROTO3, EDITIONS }

    // ── With a schema: JsonFormat's output ───────────────────────────────

    /**
     * Each payload is one message, two run together (protobuf merges them:
     * a scalar's last value, messages and groups merged, lists joined, a
     * oneof's last member), or either with fields set to their defaults
     * written after it, which a field without presence does not keep.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Syntax.class)
    @DisplayName("random schemas and payloads decode as protobuf-java's JsonFormat prints them")
    void typedAgreesWithJsonFormat(Syntax syntax) throws Exception {
        Random random = new Random(160 + syntax.ordinal());
        int decoded = 0;
        for (int round = 0; round < 300; round++) {
            Schema schema = Schema.random(syntax, random);
            String text = schema.protoText();
            FileDescriptor file = FileDescriptor.buildFrom(schema.descriptor(), new FileDescriptor[0]);
            List<Descriptor> types = new ArrayList<>();
            collect(file.getMessageTypes(), types);
            for (int sample = 0; sample < 15; sample++) {
                Descriptor type = types.get(random.nextInt(types.size()));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bytes.write(randomMessage(type, random, 0).toByteArray());
                if (random.nextInt(3) == 0) bytes.write(randomMessage(type, random, 0).toByteArray());
                if (random.nextInt(3) == 0) writeDefaults(type, random, bytes);
                byte[] wire = bytes.toByteArray();
                if (wire.length == 0) continue;   // an empty payload is refused before decoding
                String expected = JsonFormat.printer().preservingProtoFieldNames()
                      .print(DynamicMessage.parseFrom(type, wire));
                String actual = decoder.decode(HEX.formatHex(wire), text, type.getFullName());
                assertThat(JSON.readTree(actual))
                      .as("%s as %s from%n%s", HEX.formatHex(wire), type.getFullName(), text)
                      .isEqualTo(exactLongs(JSON.readTree(expected), type));
                decoded++;
            }
        }
        assertThat(decoded).isGreaterThan(3_000);
    }

    /** Every message type, groups' included, but the entries protobuf-java makes for maps. */
    private static void collect(List<Descriptor> declared, List<Descriptor> into) {
        for (Descriptor type : declared) {
            if (type.getOptions().getMapEntry()) continue;
            into.add(type);
            collect(type.getNestedTypes(), into);
        }
    }

    /**
     * JsonFormat writes 64-bit integers as strings, as proto3's JSON mapping
     * allows; the decoder writes them as exact numbers. Map keys stay strings.
     */
    private static JsonNode exactLongs(JsonNode node, Descriptor type) throws IOException {
        ObjectNode out = JSON.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            FieldDescriptor field = type.findFieldByName(entry.getKey());
            JsonNode value = entry.getValue();
            if (field.isMapField()) {
                FieldDescriptor valueField = field.getMessageType().findFieldByNumber(2);
                ObjectNode map = JSON.createObjectNode();
                for (Map.Entry<String, JsonNode> item : value.properties())
                    map.set(item.getKey(), exactLong(item.getValue(), valueField));
                out.set(entry.getKey(), map);
            } else if (field.isRepeated()) {
                ArrayNode list = JSON.createArrayNode();
                for (JsonNode item : value) list.add(exactLong(item, field));
                out.set(entry.getKey(), list);
            } else {
                out.set(entry.getKey(), exactLong(value, field));
            }
        }
        return out;
    }

    private static JsonNode exactLong(JsonNode value, FieldDescriptor field) throws IOException {
        return switch (field.getJavaType()) {
            case LONG -> JSON.readTree(value.asText());
            case MESSAGE -> exactLongs(value, field.getMessageType());
            default -> value;
        };
    }

    /** A field or two set to their default, as some encoders write them; the last setting wins. */
    private static void writeDefaults(Descriptor type, Random random, ByteArrayOutputStream bytes) throws IOException {
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        for (int i = 1 + random.nextInt(2); i > 0; i--) {
            FieldDescriptor field = type.getFields().get(random.nextInt(type.getFields().size()));
            int number = field.getNumber();
            switch (field.getType()) {
                case DOUBLE -> out.writeDouble(number, 0);
                case FLOAT -> out.writeFloat(number, 0);
                case FIXED64, SFIXED64 -> out.writeFixed64(number, 0);
                case FIXED32, SFIXED32 -> out.writeFixed32(number, 0);
                case STRING, BYTES, MESSAGE -> out.writeByteArray(number, new byte[0]);
                case GROUP -> {
                    out.writeTag(number, WireFormat.WIRETYPE_START_GROUP);
                    out.writeTag(number, WireFormat.WIRETYPE_END_GROUP);
                }
                default -> out.writeUInt64(number, 0);   // the varint types, bool and enum
            }
        }
        out.flush();
    }

    private static DynamicMessage randomMessage(Descriptor type, Random random, int depth) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
        for (OneofDescriptor oneof : type.getRealOneofs()) {
            int choice = random.nextInt(oneof.getFieldCount() + 1);
            if (choice < oneof.getFieldCount() && (depth < 3 || !isMessage(oneof.getField(choice))))
                builder.setField(oneof.getField(choice), randomValue(oneof.getField(choice), random, depth));
        }
        for (FieldDescriptor field : type.getFields()) {
            if (field.getRealContainingOneof() != null || random.nextInt(3) == 0) continue;
            if (field.isMapField()) {
                for (int i = random.nextInt(4); i > 0; i--)
                    builder.addRepeatedField(field, randomEntry(field.getMessageType(), random, depth));
            } else if (isMessage(field) && depth >= 3) {
                // Deep enough: recursive types would otherwise nest without end.
            } else if (field.isRepeated()) {
                for (int i = random.nextInt(5); i > 0; i--)
                    builder.addRepeatedField(field, randomValue(field, random, depth));
            } else {
                builder.setField(field, randomValue(field, random, depth));
            }
        }
        return builder.build();
    }

    private static boolean isMessage(FieldDescriptor field) {
        return field.getJavaType() == FieldDescriptor.JavaType.MESSAGE;
    }

    /** A map entry, its key or value sometimes left out: the decoder must default them. */
    private static DynamicMessage randomEntry(Descriptor entry, Random random, int depth) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(entry);
        FieldDescriptor key = entry.findFieldByNumber(1);
        FieldDescriptor value = entry.findFieldByNumber(2);
        if (random.nextInt(6) > 0) builder.setField(key, randomValue(key, random, depth));
        if (random.nextInt(6) > 0)
            builder.setField(value, isMessage(value) && depth >= 3
                  ? DynamicMessage.getDefaultInstance(value.getMessageType())
                  : randomValue(value, random, depth));
        return builder.build();
    }

    private static Object randomValue(FieldDescriptor field, Random random, int depth) {
        return switch (field.getJavaType()) {
            case INT -> randomInt(random);
            case LONG -> randomLong(random);
            case FLOAT -> random.nextInt(3) == 0 ? Float.intBitsToFloat(random.nextInt())
                  : random.nextInt(5) == 0 ? 0f : (float) (random.nextGaussian() * Math.pow(10, random.nextInt(20) - 10));
            case DOUBLE -> random.nextInt(3) == 0 ? Double.longBitsToDouble(random.nextLong())
                  : random.nextInt(5) == 0 ? 0.0 : random.nextGaussian() * Math.pow(10, random.nextInt(40) - 20);
            case BOOLEAN -> random.nextBoolean();
            case STRING -> randomString(random);
            case BYTE_STRING -> {
                byte[] bytes = new byte[random.nextInt(12)];
                random.nextBytes(bytes);
                yield ByteString.copyFrom(bytes);
            }
            case ENUM -> {
                EnumDescriptor type = field.getEnumType();
                // An open enum keeps a number it does not name, which JSON writes as
                // the number; a closed one (proto2's) sets it aside as unknown.
                yield !type.isClosed() && random.nextInt(5) == 0
                      ? type.findValueByNumberCreatingIfUnknown(randomInt(random))
                      : type.getValues().get(random.nextInt(type.getValues().size()));
            }
            case MESSAGE -> randomMessage(field.getMessageType(), random, depth + 1);
        };
    }

    private static int randomInt(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> 0;
            case 1 -> random.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            case 2 -> -1 - random.nextInt(300);
            case 3 -> random.nextInt(300);
            default -> random.nextInt();
        };
    }

    private static long randomLong(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> 0;
            case 1 -> random.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE;
            case 2 -> -1 - random.nextInt(300);
            case 3 -> random.nextInt();
            default -> random.nextLong();
        };
    }

    /** Valid text only: an unpaired surrogate is not a string protobuf can encode. */
    private static String randomString(Random random) {
        StringBuilder text = new StringBuilder();
        for (int i = random.nextInt(8); i > 0; i--) {
            int codePoint = switch (random.nextInt(4)) {
                case 0 -> 0x20 + random.nextInt(0x5F);
                case 1 -> random.nextInt(0x20);
                case 2 -> 0x80 + random.nextInt(0xD800 - 0x80);
                default -> 0x10000 + random.nextInt(0x100000);
            };
            text.appendCodePoint(codePoint);
        }
        return text.toString();
    }

    /**
     * A random schema, as a model that prints both the {@code .proto} text and
     * the descriptor. Every message and enum has a name of its own, so a type
     * can be referred to by any name that reaches it from where it is used:
     * relative, qualified by part or all of its package, or absolute.
     */
    static final class Schema {
        static final List<String> SCALARS = List.of("double", "float", "int64", "uint64", "int32", "fixed64",
              "fixed32", "bool", "string", "bytes", "uint32", "sfixed32", "sfixed64", "sint32", "sint64");
        static final List<String> MAP_KEYS = List.of("int64", "uint64", "int32", "fixed64", "fixed32", "bool",
              "string", "uint32", "sfixed32", "sfixed64", "sint32", "sint64");
        static final List<String> PACKAGES = List.of("", "demo", "acme.api.v1");

        final Syntax syntax;
        final String pkg;
        /** Editions only: the file sets {@code features.field_presence = IMPLICIT}. */
        final boolean fileImplicit;
        final List<Message> messages = new ArrayList<>();
        final List<EnumType> enums = new ArrayList<>();
        int groups;

        Schema(Syntax syntax, String pkg, boolean fileImplicit) {
            this.syntax = syntax;
            this.pkg = pkg;
            this.fileImplicit = fileImplicit;
        }

        static final class Message {
            final String name;
            final Message parent;
            final List<Field> fields = new ArrayList<>();
            boolean oneof;

            Message(String name, Message parent) {
                this.name = name;
                this.parent = parent;
            }

            boolean isWithin(Message other) {
                for (Message m = this; m != null; m = m.parent) if (m == other) return true;
                return false;
            }
        }

        static final class EnumType {
            final String name;
            final Message parent;
            final Map<String, Integer> values = new LinkedHashMap<>();
            boolean aliased;

            EnumType(String name, Message parent) {
                this.name = name;
                this.parent = parent;
            }
        }

        /** A field: a scalar, an enum, a message, a map, or a proto2 group. */
        static final class Field {
            String name;
            int number;
            boolean repeated;
            boolean inOneof;
            /** proto3's {@code optional}. */
            boolean optional;
            /** Editions: the field's own {@code features.field_presence}, or null. */
            String presence;
            /** The packing written: proto2's {@code packed = true}, else unpacked; null for the default. */
            Boolean packed;
            String scalar;
            Message message;
            EnumType enumType;
            String mapKey;
            Message group;
            String typeText;   // the value type as the .proto text names it
        }

        static Schema random(Syntax syntax, Random random) {
            Schema schema = new Schema(syntax, PACKAGES.get(random.nextInt(PACKAGES.size())),
                  syntax == Syntax.EDITIONS && random.nextBoolean());
            for (int i = 1 + random.nextInt(5); i > 0; i--) {
                Message parent = schema.messages.isEmpty() || random.nextBoolean() ? null
                      : schema.messages.get(random.nextInt(schema.messages.size()));
                schema.messages.add(new Message("M" + schema.messages.size(), parent));
            }
            for (int i = random.nextInt(4); i > 0; i--) {
                Message parent = random.nextBoolean() ? null : schema.messages.get(random.nextInt(schema.messages.size()));
                EnumType type = new EnumType("E" + schema.enums.size(), parent);
                type.values.put(type.name + "_ZERO", 0);
                Set<Integer> numbers = new HashSet<>(Set.of(0));
                for (int v = 1 + random.nextInt(4); v > 0; v--) {
                    int number = random.nextInt(3) == 0 ? randomInt(random) : random.nextInt(10);
                    if (numbers.add(number)) type.values.put(type.name + "_V" + type.values.size(), number);
                }
                if (random.nextInt(4) == 0) {
                    // An alias: the first name a number has is the one JSON writes.
                    type.aliased = true;
                    type.values.put(type.name + "_ALIAS", type.values.values().stream().toList()
                          .get(random.nextInt(type.values.size())));
                }
                schema.enums.add(type);
            }
            for (Message message : List.copyOf(schema.messages)) schema.addFields(message, random, 0);
            return schema;
        }

        private void addFields(Message message, Random random, int groupDepth) {
            Set<Integer> numbers = new HashSet<>();
            List<Field> plain = new ArrayList<>();
            for (int i = 1 + random.nextInt(8); i > 0; i--) {
                Field field = new Field();
                do field.number = switch (random.nextInt(10)) {
                    case 0 -> 1 + random.nextInt(536_870_911);
                    case 1, 2 -> 1 + random.nextInt(18_999);
                    default -> 1 + random.nextInt(30);
                }; while (field.number >= 19_000 && field.number <= 19_999 || !numbers.add(field.number));
                field.name = "f" + field.number;
                int kind = random.nextInt(11);
                if (kind == 10 && syntax == Syntax.PROTO2 && groupDepth < 2) {
                    // A group: a message declared where the field is, its name
                    // capitalised, that may declare types of its own.
                    field.group = new Message("G" + groups++, message);
                    field.name = field.group.name.toLowerCase(Locale.ROOT);
                    if (random.nextInt(3) == 0) {
                        EnumType inner = new EnumType("E" + enums.size(), field.group);
                        inner.values.put(inner.name + "_ZERO", 0);
                        inner.values.put(inner.name + "_ONE", 1);
                        enums.add(inner);
                    }
                    if (random.nextInt(3) == 0) {
                        Message inner = new Message("M" + messages.size(), field.group);
                        messages.add(inner);
                        addFields(inner, random, groupDepth + 1);
                    }
                    addFields(field.group, random, groupDepth + 1);
                } else if (kind < 5 || kind == 10) {
                    field.scalar = SCALARS.get(random.nextInt(SCALARS.size()));
                    field.typeText = field.scalar;
                } else if (kind < 7 && !enums.isEmpty()) {
                    field.enumType = enums.get(random.nextInt(enums.size()));
                    field.typeText = reference(field.enumType.parent, field.enumType.name, message, random);
                } else if (kind < 9) {
                    field.message = messages.get(random.nextInt(messages.size()));
                    field.typeText = reference(field.message.parent, field.message.name, message, random);
                } else {
                    field.mapKey = MAP_KEYS.get(random.nextInt(MAP_KEYS.size()));
                    int value = random.nextInt(3);
                    if (value == 0 && !enums.isEmpty()) {
                        field.enumType = enums.get(random.nextInt(enums.size()));
                        field.typeText = reference(field.enumType.parent, field.enumType.name, message, random);
                    } else if (value == 1) {
                        field.message = messages.get(random.nextInt(messages.size()));
                        field.typeText = reference(field.message.parent, field.message.name, message, random);
                    } else {
                        field.scalar = SCALARS.get(random.nextInt(SCALARS.size()));
                        field.typeText = field.scalar;
                    }
                }
                boolean singularValue = field.scalar != null || field.enumType != null;
                if (field.mapKey == null && random.nextInt(3) == 0) {
                    field.repeated = true;
                    boolean packable = field.enumType != null
                          || field.scalar != null && !field.scalar.equals("string") && !field.scalar.equals("bytes");
                    // proto2 packs only when asked to; proto3 and editions unless asked not to.
                    if (packable && random.nextInt(3) == 0) field.packed = syntax == Syntax.PROTO2;
                } else if (field.mapKey == null && singularValue && syntax == Syntax.PROTO3) {
                    field.optional = random.nextInt(4) == 0;
                } else if (field.mapKey == null && singularValue && syntax == Syntax.EDITIONS) {
                    int presence = random.nextInt(4);
                    field.presence = presence == 0 ? "IMPLICIT" : presence == 1 ? "EXPLICIT" : null;
                }
                plain.add(field);
            }
            // Members of a oneof come last, together, as the descriptor needs them.
            List<Field> candidates = plain.stream().filter(f -> !f.repeated && f.mapKey == null && f.group == null
                  && !f.optional && f.presence == null).toList();
            if (candidates.size() >= 2 && random.nextBoolean()) {
                message.oneof = true;
                for (Field field : candidates) field.inOneof = random.nextBoolean();
                candidates.getFirst().inOneof = true;   // a oneof holds at least one field
            }
            plain.stream().filter(f -> !f.inOneof).forEach(message.fields::add);
            plain.stream().filter(f -> f.inOneof).forEach(message.fields::add);
        }

        /** A name for a type declared in {@code owner} (null: the file) that reaches it from {@code from}. */
        private String reference(Message owner, String name, Message from, Random random) {
            List<String> path = new ArrayList<>();
            path.add(name);
            for (Message m = owner; m != null; m = m.parent) path.addFirst(m.name);
            List<String> packageParts = pkg.isEmpty() ? List.of() : List.of(pkg.split("\\."));
            List<String> names = new ArrayList<>();
            String qualified = String.join(".", path);
            names.add("." + (pkg.isEmpty() ? "" : pkg + ".") + qualified);
            for (int i = 0; i < packageParts.size(); i++)
                names.add(String.join(".", packageParts.subList(i, packageParts.size())) + "." + qualified);
            // Relative: from each message on the path that encloses the field's message.
            Message enclosing = owner;
            for (int i = path.size() - 1; i >= 0; i--) {
                if (enclosing == null || from.isWithin(enclosing))
                    names.add(String.join(".", path.subList(i, path.size())));
                if (enclosing == null) break;
                enclosing = enclosing.parent;
            }
            return names.get(random.nextInt(names.size()));
        }

        String fullName(Message owner, String name) {
            StringBuilder full = new StringBuilder(name);
            for (Message m = owner; m != null; m = m.parent) full.insert(0, m.name + ".");
            return pkg.isEmpty() ? full.toString() : pkg + "." + full;
        }

        // ── As .proto text ──

        String protoText() {
            StringBuilder out = new StringBuilder(syntax == Syntax.EDITIONS ? "edition = \"2023\";\n"
                  : "syntax = \"" + syntax.name().toLowerCase(Locale.ROOT) + "\";\n");
            if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n");
            if (fileImplicit) out.append("option features.field_presence = IMPLICIT;\n");
            for (Message message : messages) if (message.parent == null) printMessage(out, message, "");
            for (EnumType type : enums) if (type.parent == null) printEnum(out, type, "");
            return out.toString();
        }

        private void printMessage(StringBuilder out, Message message, String indent) {
            out.append(indent).append("message ").append(message.name).append(" {\n");
            printFields(out, message, indent + "  ");
            for (Message nested : messages) if (nested.parent == message) printMessage(out, nested, indent + "  ");
            for (EnumType type : enums) if (type.parent == message) printEnum(out, type, indent + "  ");
            out.append(indent).append("}\n");
        }

        private void printFields(StringBuilder out, Message message, String indent) {
            for (Field field : message.fields) if (!field.inOneof) printField(out, field, indent);
            if (message.oneof) {
                out.append(indent).append("oneof choice {\n");
                for (Field field : message.fields) if (field.inOneof) printField(out, field, indent + "  ");
                out.append(indent).append("}\n");
            }
        }

        private void printField(StringBuilder out, Field field, String indent) {
            out.append(indent);
            if (field.repeated) out.append("repeated ");
            else if (field.optional || syntax == Syntax.PROTO2 && !field.inOneof && field.mapKey == null)
                out.append("optional ");
            if (field.group != null) {
                out.append("group ").append(field.group.name).append(" = ").append(field.number).append(" {\n");
                printFields(out, field.group, indent + "  ");
                for (Message nested : messages)
                    if (nested.parent == field.group) printMessage(out, nested, indent + "  ");
                for (EnumType type : enums) if (type.parent == field.group) printEnum(out, type, indent + "  ");
                out.append(indent).append("}\n");
                return;
            }
            if (field.mapKey != null) out.append("map<").append(field.mapKey).append(", ").append(field.typeText).append(">");
            else out.append(field.typeText);
            out.append(' ').append(field.name).append(" = ").append(field.number);
            List<String> options = new ArrayList<>();
            if (field.packed != null)
                options.add(syntax == Syntax.EDITIONS ? "features.repeated_field_encoding = EXPANDED" : "packed = " + field.packed);
            if (field.presence != null) options.add("features.field_presence = " + field.presence);
            if (!options.isEmpty()) out.append(" [").append(String.join(", ", options)).append("]");
            out.append(";\n");
        }

        private static void printEnum(StringBuilder out, EnumType type, String indent) {
            out.append(indent).append("enum ").append(type.name).append(" {\n");
            if (type.aliased) out.append(indent).append("  option allow_alias = true;\n");
            type.values.forEach((name, number) ->
                  out.append(indent).append("  ").append(name).append(" = ").append(number).append(";\n"));
            out.append(indent).append("}\n");
        }

        // ── As a descriptor ──

        FileDescriptorProto descriptor() {
            FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder().setName("random.proto");
            if (syntax == Syntax.EDITIONS) file.setSyntax("editions").setEdition(Edition.EDITION_2023);
            else file.setSyntax(syntax.name().toLowerCase(Locale.ROOT));
            if (!pkg.isEmpty()) file.setPackage(pkg);
            if (fileImplicit)
                file.setOptions(FileOptions.newBuilder().setFeatures(
                      FeatureSet.newBuilder().setFieldPresence(FeatureSet.FieldPresence.IMPLICIT)));
            for (Message message : messages) if (message.parent == null) file.addMessageType(describe(message));
            for (EnumType type : enums) if (type.parent == null) file.addEnumType(describe(type));
            return file.build();
        }

        private DescriptorProto describe(Message message) {
            DescriptorProto.Builder out = DescriptorProto.newBuilder().setName(message.name);
            if (message.oneof) out.addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"));
            for (Field field : message.fields) {
                FieldDescriptorProto.Builder described = FieldDescriptorProto.newBuilder()
                      .setName(field.name).setNumber(field.number)
                      .setLabel(field.repeated || field.mapKey != null ? FieldDescriptorProto.Label.LABEL_REPEATED
                            : FieldDescriptorProto.Label.LABEL_OPTIONAL);
                if (field.group != null) {
                    out.addNestedType(describe(field.group));
                    described.setType(FieldDescriptorProto.Type.TYPE_GROUP)
                          .setTypeName("." + fullName(message, field.group.name));
                } else if (field.mapKey != null) {
                    // protoc's own entry type: <Name>Entry, nested, keyed by field 1.
                    String entryName = Character.toUpperCase(field.name.charAt(0)) + field.name.substring(1) + "Entry";
                    DescriptorProto.Builder entry = DescriptorProto.newBuilder().setName(entryName)
                          .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                          .addField(FieldDescriptorProto.newBuilder().setName("key").setNumber(1)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setType(scalarType(field.mapKey)));
                    FieldDescriptorProto.Builder value = FieldDescriptorProto.newBuilder().setName("value").setNumber(2)
                          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
                    typeOf(field, value);
                    out.addNestedType(entry.addField(value));
                    described.setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                          .setTypeName("." + fullName(message, entryName));
                } else {
                    typeOf(field, described);
                }
                FeatureSet.Builder features = FeatureSet.newBuilder();
                if (field.packed != null) {
                    if (syntax == Syntax.EDITIONS) features.setRepeatedFieldEncoding(FeatureSet.RepeatedFieldEncoding.EXPANDED);
                    else described.setOptions(FieldOptions.newBuilder().setPacked(field.packed));
                }
                if (field.presence != null) features.setFieldPresence(FeatureSet.FieldPresence.valueOf(field.presence));
                if (field.packed != null && syntax == Syntax.EDITIONS || field.presence != null)
                    described.setOptions(FieldOptions.newBuilder().setFeatures(features));
                if (field.inOneof) described.setOneofIndex(0);
                if (field.optional) {
                    // proto3's optional is a oneof of its own, after the real ones.
                    described.setProto3Optional(true).setOneofIndex(out.getOneofDeclCount());
                    out.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_" + field.name));
                }
                out.addField(described);
            }
            for (Message nested : messages) if (nested.parent == message) out.addNestedType(describe(nested));
            for (EnumType type : enums) if (type.parent == message) out.addEnumType(describe(type));
            return out.build();
        }

        private void typeOf(Field field, FieldDescriptorProto.Builder described) {
            if (field.scalar != null) {
                described.setType(scalarType(field.scalar));
            } else if (field.message != null) {
                described.setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                      .setTypeName("." + fullName(field.message.parent, field.message.name));
            } else {
                described.setType(FieldDescriptorProto.Type.TYPE_ENUM)
                      .setTypeName("." + fullName(field.enumType.parent, field.enumType.name));
            }
        }

        private static EnumDescriptorProto describe(EnumType type) {
            EnumDescriptorProto.Builder out = EnumDescriptorProto.newBuilder().setName(type.name);
            if (type.aliased) out.setOptions(EnumOptions.newBuilder().setAllowAlias(true));
            type.values.forEach((name, number) ->
                  out.addValue(EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number)));
            return out.build();
        }

        private static FieldDescriptorProto.Type scalarType(String scalar) {
            return FieldDescriptorProto.Type.valueOf("TYPE_" + scalar.toUpperCase(Locale.ROOT));
        }
    }

    // ── Without a schema: protobuf-java's parser ─────────────────────────

    private static final Pattern LENGTH = Pattern.compile("holds a length of (-?[\\d,]+) bytes");

    /**
     * Valid wire data, then the same data mutated: the decoder reads what
     * protobuf-java reads and refuses what it refuses, but for three cases
     * where protobuf-java is the lenient one. It keeps only the low 32 bits of
     * a tag or a length, so one with more set above them still reads; and it
     * ends a varint at its tenth byte whatever that byte says. The decoder
     * refuses all three, as upb, protobuf's C parser, does.
     */
    @Test @DisplayName("random and mutated wire data: read and refused as protobuf-java's parser does")
    void rawAgreesWithUnknownFieldSet() throws Exception {
        Random random = new Random(20_261_004);
        int read = 0;
        int refused = 0;
        for (int round = 0; round < 60_000; round++) {
            byte[] payload = mutate(randomWire(random, 0), random);
            if (payload.length == 0) continue;
            String hex = HEX.formatHex(payload);
            UnknownFieldSet expected;
            try {
                expected = UnknownFieldSet.parseFrom(payload);
            } catch (InvalidProtocolBufferException refusedByJava) {
                expected = null;
            }
            JsonNode actual;
            try {
                actual = JSON.readTree(decoder.decode(hex, "", ""));
            } catch (ProtoPayloadDecoder.MalformedPayload refusedHere) {
                if (expected != null)
                    assertThat(refusedOnlyHere(refusedHere.getMessage())).as("%s: %s", hex, refusedHere.getMessage())
                          .isTrue();
                refused++;
                continue;
            }
            assertThat(expected).as("%s decoded to %s, which protobuf-java refuses", hex, actual).isNotNull();
            sameFields(actual, expected, hex);
            read++;
        }
        // Both sides of the line are well exercised.
        assertThat(read).isGreaterThan(10_000);
        assertThat(refused).isGreaterThan(10_000);
    }

    /** The refusals protobuf-java does not share: bits past the 32nd in a tag or a length; an 11th varint byte. */
    private static boolean refusedOnlyHere(String message) {
        if (message.contains("has a field number past 536,870,911")
              || message.contains("holds a varint longer than 10 bytes")) return true;
        Matcher length = LENGTH.matcher(message);
        return length.find() && new BigInteger(length.group(1).replace(",", "")).bitLength() > 32;
    }

    /**
     * The same fields, each seen as many times; varints the same values, in
     * order; every 32- and 64-bit value present, in the hex the decoder writes.
     */
    private static void sameFields(JsonNode actual, UnknownFieldSet expected, String hex) {
        assertThat(actual.isObject()).as(hex).isTrue();
        Set<String> numbers = new HashSet<>();
        actual.fieldNames().forEachRemaining(numbers::add);
        assertThat(numbers).as(hex).isEqualTo(expected.asMap().keySet().stream().map(String::valueOf)
              .collect(java.util.stream.Collectors.toSet()));
        expected.asMap().forEach((number, field) -> {
            JsonNode value = actual.get(String.valueOf(number));
            List<JsonNode> values = new ArrayList<>();
            if (value.isArray()) value.forEach(values::add);
            else values.add(value);
            int count = field.getVarintList().size() + field.getFixed32List().size() + field.getFixed64List().size()
                  + field.getLengthDelimitedList().size() + field.getGroupList().size();
            assertThat(values).as("%s field %d", hex, number).hasSize(count);
            assertThat(values.stream().filter(JsonNode::isNumber).map(JsonNode::bigIntegerValue).toList())
                  .as("%s field %d varints", hex, number)
                  .isEqualTo(field.getVarintList().stream().map(v -> new BigInteger(Long.toUnsignedString(v))).toList());
            List<String> texts = values.stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList();
            for (int v : field.getFixed32List())
                assertThat(texts).as("%s field %d", hex, number).contains(String.format("0x%08x", v));
            for (long v : field.getFixed64List())
                assertThat(texts).as("%s field %d", hex, number).contains(String.format("0x%016x", v));
        });
    }

    /** Valid wire data of every wire type, groups and nested messages included. */
    private static byte[] randomWire(Random random, int depth) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        for (int i = random.nextInt(depth == 0 ? 6 : 4); i >= 0; i--) {
            int number = random.nextInt(8) == 0 ? 1 + random.nextInt(536_870_911) : 1 + random.nextInt(20);
            switch (random.nextInt(depth < 4 ? 7 : 5)) {
                case 0, 1 -> out.writeUInt64(number, randomLong(random));
                case 2 -> out.writeFixed32(number, randomInt(random));
                case 3 -> out.writeFixed64(number, randomLong(random));
                case 4 -> {
                    byte[] value = new byte[random.nextInt(10)];
                    if (random.nextBoolean()) random.nextBytes(value);
                    else value = randomString(random).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeByteArray(number, value);
                }
                case 5 -> out.writeByteArray(number, randomWire(random, depth + 1));
                default -> {
                    out.writeTag(number, WireFormat.WIRETYPE_START_GROUP);
                    out.writeRawBytes(randomWire(random, depth + 1));
                    out.writeTag(number, WireFormat.WIRETYPE_END_GROUP);
                }
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    /** Left alone a third of the time; else a byte changed, inserted, dropped, or the end cut off. */
    private static byte[] mutate(byte[] bytes, Random random) {
        byte[] result = bytes;
        for (int i = random.nextInt(3) == 0 ? 0 : 1 + random.nextInt(3); i > 0 && result.length > 0; i--) {
            int at = random.nextInt(result.length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            switch (random.nextInt(4)) {
                case 0 -> {
                    out.write(result, 0, result.length);
                    byte[] changed = out.toByteArray();
                    changed[at] = (byte) random.nextInt(256);
                    result = changed;
                    continue;
                }
                case 1 -> {
                    out.write(result, 0, at);
                    out.write(random.nextInt(256));
                    out.write(result, at, result.length - at);
                }
                case 2 -> {
                    out.write(result, 0, at);
                    out.write(result, at + 1, result.length - at - 1);
                }
                default -> out.write(result, 0, at);
            }
            result = out.toByteArray();
        }
        return result;
    }

    @Test @DisplayName("groups nested to protobuf's limit are read; one deeper is refused, by both")
    void groupDepthAgrees() throws Exception {
        for (int depth : new int[]{ProtoPayloadDecoder.MAX_DEPTH, ProtoPayloadDecoder.MAX_DEPTH + 1}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            for (int i = 0; i < depth; i++) out.writeTag(1, WireFormat.WIRETYPE_START_GROUP);
            out.writeInt32(2, 7);
            for (int i = 0; i < depth; i++) out.writeTag(1, WireFormat.WIRETYPE_END_GROUP);
            out.flush();
            byte[] payload = bytes.toByteArray();
            boolean javaReads;
            try {
                UnknownFieldSet.parseFrom(payload);
                javaReads = true;
            } catch (InvalidProtocolBufferException tooDeep) {
                javaReads = false;
            }
            boolean decoderReads;
            try {
                decoder.decode(HEX.formatHex(payload), "", "");
                decoderReads = true;
            } catch (ProtoPayloadDecoder.MalformedPayload tooDeep) {
                decoderReads = false;
            }
            assertThat(decoderReads).as("%d groups deep", depth).isEqualTo(javaReads)
                  .isEqualTo(depth <= ProtoPayloadDecoder.MAX_DEPTH);
        }
    }
}
