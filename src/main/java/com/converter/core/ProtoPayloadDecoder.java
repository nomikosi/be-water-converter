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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes a binary Protobuf message, pasted as hex or base64, into JSON.
 *
 * <p>Without a schema it reads the wire format as {@code protoc --decode_raw}
 * does: keys are field numbers, varints are unsigned numbers, 64- and 32-bit
 * values are hex (their type is unknown), and a length-delimited value is a
 * nested message when its bytes read as one, else text when they are UTF-8,
 * else base64. A field number seen more than once becomes a list.
 *
 * <p>With a schema and the message type the payload holds, it writes what
 * protobuf's own JSON printer writes for it: fields under their names (or
 * {@code json_name}), enums by name, bytes in base64, NaN and the infinities
 * as strings, packed and unpacked repeated fields alike, maps as objects, and
 * no field without presence (proto3's, by default) that holds its default. It
 * reads the payload as protobuf parses it: a field set twice keeps its last
 * value, a singular message or group set twice is merged, and of a oneof's
 * members only the one set last is kept. Fields the schema does not declare,
 * and messages from files it only imports, are decoded raw; a field whose wire
 * type contradicts the schema is an error, which usually means the wrong
 * message type was chosen. 64-bit integers are written as exact JSON numbers
 * rather than strings.
 */
public final class ProtoPayloadDecoder {

    /** How deep messages may nest, as protobuf's own parsers allow. */
    static final int MAX_DEPTH = 100;

    private static final int VARINT = 0;
    private static final int I64 = 1;
    private static final int LEN = 2;
    private static final int SGROUP = 3;
    private static final int EGROUP = 4;
    private static final int I32 = 5;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ProtoConverter proto;
    private String cachedSchemaText;
    private ProtoSchema cachedSchema;

    public ProtoPayloadDecoder(ProtoConverter proto) {
        this.proto = proto;
    }

    /**
     * @param payloadText the payload, as hex or base64
     * @param schemaText  the {@code .proto} text to decode against, or empty to decode raw
     * @param messageType the fully qualified message type the payload holds, or empty to decode raw
     * @return the payload as compact JSON
     */
    public String decode(String payloadText, String schemaText, String messageType) throws Exception {
        byte[] bytes = ProtoPayloadText.bytes(payloadText);
        JsonNode decoded;
        if (schemaText == null || schemaText.isBlank() || messageType == null || messageType.isBlank()) {
            decoded = raw(new Wire(bytes, 0, bytes.length), 0, -1);
        } else {
            ProtoSchema schema = schema(schemaText);
            ProtoSchema.Message message = schema.messages().get(messageType);
            if (message == null)
                throw new IllegalArgumentException("The schema declares no message " + messageType
                      + ". It declares " + String.join(", ", schema.messageNames()) + ".");
            decoded = typed(new Wire(bytes, 0, bytes.length), message, schema, 0, -1);
        }
        return LenientJson.COMPACT.writeValueAsString(decoded);
    }

    /** The schema for this text, read once while the text stays the same. */
    private synchronized ProtoSchema schema(String schemaText) {
        if (!schemaText.equals(cachedSchemaText)) {
            cachedSchema = proto.readSchema(schemaText);
            cachedSchemaText = schemaText;
        }
        return cachedSchema;
    }

    // ── Wire format ───────────────────────────────────────────────────────

    /** A payload that does not follow the wire format, at a byte it names. */
    static final class MalformedPayload extends IllegalArgumentException {
        MalformedPayload(String message) {
            super(message);
        }
    }

    /**
     * Reads the wire format over {@code bytes[pos, end)}. The bytes are the
     * payload's, or for a message set more than once, its parts joined, with
     * where each byte came from: an error names the byte in the payload.
     */
    private static final class Wire {
        final byte[] bytes;
        final int end;
        /** Each byte's offset in the payload, or null when the bytes are the payload's own. */
        final int[] origins;
        int pos;

        Wire(byte[] bytes, int start, int end) {
            this(bytes, start, end, null);
        }

        private Wire(byte[] bytes, int start, int end, int[] origins) {
            this.bytes = bytes;
            this.pos = start;
            this.end = end;
            this.origins = origins;
        }

        /** The bytes {@code [start, end)} of these, read on their own. */
        Wire slice(int start, int end) {
            return new Wire(bytes, start, end, origins);
        }

        /** Where the byte at {@code position} here is in the payload; the end is just past the last. */
        int origin(int position) {
            if (origins == null) return position;
            if (position < origins.length) return origins[position];
            return origins.length == 0 ? 0 : origins[origins.length - 1] + 1;
        }

        boolean atEnd() {
            return pos >= end;
        }

        long varint() {
            int start = pos;
            long result = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                if (pos >= end) throw malformed(origin(start), "ends inside a varint");
                byte b = bytes[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw malformed(origin(start), "holds a varint longer than 10 bytes");
        }

        int fixed32() {
            if (end - pos < 4) throw malformed(origin(pos), "ends inside a 32-bit value");
            int value = (bytes[pos] & 0xFF) | (bytes[pos + 1] & 0xFF) << 8 | (bytes[pos + 2] & 0xFF) << 16
                  | (bytes[pos + 3] & 0xFF) << 24;
            pos += 4;
            return value;
        }

        long fixed64() {
            if (end - pos < 8) throw malformed(origin(pos), "ends inside a 64-bit value");
            long value = 0;
            for (int i = 7; i >= 0; i--) value = value << 8 | (bytes[pos + i] & 0xFF);
            pos += 8;
            return value;
        }

        /** A length prefix, checked against what remains. */
        int length() {
            int at = pos;
            long length = varint();
            if (length < 0 || length > end - pos)
                throw malformed(origin(at), String.format(Locale.ROOT, "holds a length of %,d bytes where %,d remain",
                      length, end - pos));
            return (int) length;
        }
    }

    private static MalformedPayload malformed(int at, String problem) {
        return new MalformedPayload(String.format(Locale.ROOT, "The payload %s at byte %,d.", problem, at));
    }

    /**
     * A tag: the field number and wire type, checked as protobuf checks them.
     *
     * @param at     where the tag starts in the bytes read
     * @param origin where it starts in the payload, for errors to name
     */
    private record Tag(int number, int wireType, int at, int origin) {}

    private static Tag tag(Wire wire) {
        int at = wire.pos;
        int origin = wire.origin(at);
        long tag = wire.varint();
        int wireType = (int) (tag & 7);
        long number = tag >>> 3;
        if (number == 0) throw malformed(origin, "has a field numbered 0");
        if (number > 536_870_911) throw malformed(origin, "has a field number past 536,870,911");
        if (wireType == 6 || wireType == 7)
            throw malformed(origin, "has wire type " + wireType + ", which protobuf does not define");
        return new Tag((int) number, wireType, at, origin);
    }

    private static String wireTypeName(int wireType) {
        return switch (wireType) {
            case VARINT -> "a varint";
            case I64 -> "a 64-bit value";
            case LEN -> "a length-delimited value";
            case SGROUP -> "a group";
            case EGROUP -> "the end of a group";
            default -> "a 32-bit value";
        };
    }

    private static void checkDepth(int depth, Wire wire) {
        if (depth > MAX_DEPTH)
            throw malformed(wire.origin(wire.pos), String.format(Locale.ROOT,
                  "nests messages more than %d deep, the limit protobuf's own parsers keep", MAX_DEPTH));
    }

    // ── Raw ───────────────────────────────────────────────────────────────

    /**
     * A message read without a schema, up to the end of {@code wire} or, for a
     * group ({@code group} its field number, else -1), up to its end tag.
     */
    private static ObjectNode raw(Wire wire, int depth, int group) {
        checkDepth(depth, wire);
        Map<Integer, List<JsonNode>> fields = new LinkedHashMap<>();
        while (!wire.atEnd()) {
            Tag tag = tag(wire);
            if (tag.wireType() == EGROUP) {
                if (tag.number() == group) return object(fields);
                throw malformed(tag.origin(), "ends group " + tag.number() + ", which was never started");
            }
            fields.computeIfAbsent(tag.number(), n -> new ArrayList<>()).add(rawValue(wire, tag, depth));
        }
        if (group >= 0) throw malformed(wire.origin(wire.pos), "ends before group " + group + " is closed");
        return object(fields);
    }

    private static JsonNode rawValue(Wire wire, Tag tag, int depth) {
        return switch (tag.wireType()) {
            case VARINT -> unsigned(wire.varint());
            case I64 -> NODES.textNode(String.format(Locale.ROOT, "0x%016x", wire.fixed64()));
            case I32 -> NODES.textNode(String.format(Locale.ROOT, "0x%08x", wire.fixed32()));
            case LEN -> {
                int length = wire.length();
                JsonNode value = rawLengthDelimited(wire.slice(wire.pos, wire.pos + length), depth);
                wire.pos += length;
                yield value;
            }
            default -> raw(wire, depth + 1, tag.number());   // a group, read to its end tag
        };
    }

    /**
     * A nested message when the bytes read as one, as protoc tries first; else
     * UTF-8 text; else base64.
     */
    private static JsonNode rawLengthDelimited(Wire value, int depth) {
        if (value.atEnd()) return NODES.textNode("");
        if (depth < MAX_DEPTH) {
            try {
                return raw(value.slice(value.pos, value.end), depth + 1, -1);
            } catch (MalformedPayload notAMessage) {
                // Text or bytes, then.
            }
        }
        String text = utf8(value.bytes, value.pos, value.end);
        return NODES.textNode(text != null ? text : base64(value.bytes, value.pos, value.end));
    }

    private static ObjectNode object(Map<Integer, List<JsonNode>> fields) {
        ObjectNode node = NODES.objectNode();
        fields.forEach((number, values) -> node.set(String.valueOf(number),
              values.size() == 1 ? values.getFirst() : NODES.arrayNode().addAll(values)));
        return node;
    }

    // ── With a schema ─────────────────────────────────────────────────────

    /** Where a singular message's or group's occurrences lie: protobuf merges them into one. */
    private record Range(int start, int end) {}

    /**
     * A message read against its type, up to the end of {@code wire} or, for a
     * group ({@code group} its field number, else -1), up to its end tag.
     */
    private ObjectNode typed(Wire wire, ProtoSchema.Message message, ProtoSchema schema, int depth, int group) {
        checkDepth(depth, wire);
        Map<Integer, List<JsonNode>> values = new LinkedHashMap<>();
        Map<Integer, List<Range>> singularMessages = new LinkedHashMap<>();
        Map<Integer, ObjectNode> maps = new LinkedHashMap<>();
        Map<Integer, List<JsonNode>> unknown = new LinkedHashMap<>();
        // Each oneof's member set last: setting one clears the member set before it.
        Map<String, Integer> oneofMembers = new HashMap<>();
        boolean closed = group < 0;
        while (!wire.atEnd()) {
            Tag tag = tag(wire);
            if (tag.wireType() == EGROUP) {
                if (tag.number() == group) {
                    closed = true;
                    break;
                }
                throw malformed(tag.origin(), "ends group " + tag.number() + ", which was never started");
            }
            ProtoSchema.Field field = message.fields().get(tag.number());
            if (field == null) {
                unknown.computeIfAbsent(tag.number(), n -> new ArrayList<>()).add(rawValue(wire, tag, depth));
                continue;
            }
            if (field.oneof() != null) {
                Integer previous = oneofMembers.put(field.oneof(), tag.number());
                // Back to a member after another was set: what it held before was cleared.
                if (previous != null && previous != tag.number()) {
                    values.remove(tag.number());
                    singularMessages.remove(tag.number());
                }
            }
            switch (field.kind()) {
                case SCALAR, ENUM -> readScalars(wire, tag, field, schema, message,
                      values.computeIfAbsent(tag.number(), n -> new ArrayList<>()));
                case MESSAGE -> {
                    expect(tag, LEN, field, message);
                    int length = wire.length();
                    if (field.repeated()) {
                        values.computeIfAbsent(tag.number(), n -> new ArrayList<>()).add(typed(
                              wire.slice(wire.pos, wire.pos + length), messageType(schema, field.type()),
                              schema, depth + 1, -1));
                    } else {
                        singularMessages.computeIfAbsent(tag.number(), n -> new ArrayList<>())
                              .add(new Range(wire.pos, wire.pos + length));
                    }
                    wire.pos += length;
                }
                case GROUP -> {
                    expect(tag, SGROUP, field, message);
                    if (field.repeated()) {
                        values.computeIfAbsent(tag.number(), n -> new ArrayList<>()).add(
                              typed(wire, messageType(schema, field.type()), schema, depth + 1, tag.number()));
                    } else {
                        // Merged like a message: its fields, between its start and end tags.
                        int start = wire.pos;
                        int end = skipGroup(wire, depth + 1, tag.number());
                        singularMessages.computeIfAbsent(tag.number(), n -> new ArrayList<>()).add(new Range(start, end));
                    }
                }
                case MAP -> {
                    expect(tag, LEN, field, message);
                    int length = wire.length();
                    mapEntry(wire.slice(wire.pos, wire.pos + length), field, schema, depth,
                          maps.computeIfAbsent(tag.number(), n -> NODES.objectNode()));
                    wire.pos += length;
                }
                case UNRESOLVED -> values.computeIfAbsent(tag.number(), n -> new ArrayList<>())
                      .add(rawValue(wire, tag, depth));
            }
        }
        if (!closed) throw malformed(wire.origin(wire.pos), "ends before group " + group + " is closed");

        ObjectNode node = NODES.objectNode();
        for (ProtoSchema.Field field : message.fields().values()) {
            int number = field.number();
            if (field.oneof() != null && !Integer.valueOf(number).equals(oneofMembers.get(field.oneof()))) continue;
            if (maps.containsKey(number)) {
                node.set(field.jsonKey(), maps.get(number));
            } else if (singularMessages.containsKey(number)) {
                node.set(field.jsonKey(), mergedMessage(wire, singularMessages.get(number),
                      messageType(schema, field.type()), schema, depth));
            } else if (values.containsKey(number)) {
                List<JsonNode> list = values.get(number);
                if (field.repeated()) node.set(field.jsonKey(), NODES.arrayNode().addAll(list));
                else if (!field.implicitPresence() || !isDefault(list.getLast(), field, schema))
                    node.set(field.jsonKey(), list.getLast());
            }
        }
        unknown.forEach((number, list) -> node.set(String.valueOf(number),
              list.size() == 1 ? list.getFirst() : NODES.arrayNode().addAll(list)));
        return node;
    }

    /**
     * A singular message's occurrences in {@code source}, merged as protobuf
     * merges them: by reading them as one, each byte keeping its place in the
     * payload.
     */
    private ObjectNode mergedMessage(Wire source, List<Range> ranges, ProtoSchema.Message type, ProtoSchema schema,
          int depth) {
        if (ranges.size() == 1)
            return typed(source.slice(ranges.getFirst().start(), ranges.getFirst().end()), type, schema, depth + 1, -1);
        int size = 0;
        for (Range range : ranges) size += range.end() - range.start();
        byte[] joined = new byte[size];
        int[] origins = new int[size];
        int at = 0;
        for (Range range : ranges) {
            for (int i = range.start(); i < range.end(); i++, at++) {
                joined[at] = source.bytes[i];
                origins[at] = source.origin(i);
            }
        }
        return typed(new Wire(joined, 0, size, origins), type, schema, depth + 1, -1);
    }

    /**
     * Reads past a group's fields to its end tag, checking them as {@link #raw}
     * does, and returns where the end tag starts.
     */
    private static int skipGroup(Wire wire, int depth, int group) {
        checkDepth(depth, wire);
        while (!wire.atEnd()) {
            Tag tag = tag(wire);
            switch (tag.wireType()) {
                case VARINT -> wire.varint();
                case I64 -> wire.fixed64();
                case I32 -> wire.fixed32();
                case LEN -> {
                    int length = wire.length();
                    wire.pos += length;
                }
                case SGROUP -> skipGroup(wire, depth + 1, tag.number());
                default -> {
                    if (tag.number() == group) return tag.at();
                    throw malformed(tag.origin(), "ends group " + tag.number() + ", which was never started");
                }
            }
        }
        throw malformed(wire.origin(wire.pos), "ends before group " + group + " is closed");
    }

    /**
     * Whether a value is its field's default, which a field without presence
     * does not tell apart from no value. -0.0 is not: protobuf writes it.
     */
    private static boolean isDefault(JsonNode value, ProtoSchema.Field field, ProtoSchema schema) {
        if (field.kind() == ProtoSchema.Kind.ENUM)
            return value.isNumber() ? value.intValue() == 0
                  : value.asText().equals(schema.enums().get(field.type()).names().get(0));
        return switch (field.type()) {
            case "string", "bytes" -> value.asText().isEmpty();
            case "bool" -> !value.booleanValue();
            case "float", "double" -> value.isNumber() && Double.doubleToRawLongBits(value.doubleValue()) == 0;
            default -> value.bigIntegerValue().signum() == 0;
        };
    }

    private static ProtoSchema.Message messageType(ProtoSchema schema, String fullName) {
        ProtoSchema.Message type = schema.messages().get(fullName);
        if (type == null) throw new IllegalStateException("No message " + fullName + " in the schema");
        return type;
    }

    private static void expect(Tag tag, int wireType, ProtoSchema.Field field, ProtoSchema.Message message) {
        if (tag.wireType() != wireType) throw mismatch(tag, field, message);
    }

    private static MalformedPayload mismatch(Tag tag, ProtoSchema.Field field, ProtoSchema.Message message) {
        String declared = switch (field.kind()) {
            case MESSAGE -> "a message " + field.type();
            case GROUP -> "a group";
            case MAP -> "a map";
            case ENUM -> "an enum " + field.type();
            default -> field.type();
        };
        return new MalformedPayload(String.format(Locale.ROOT,
              "Field %s (number %d) of %s is declared %s, but the payload holds %s for it at byte %,d. "
                    + "Is %s the right message type?",
              field.name(), field.number(), message.fullName(), declared, wireTypeName(tag.wireType()),
              tag.origin(), message.fullName()));
    }

    /** One scalar or enum value, or a packed run of them. */
    private static void readScalars(Wire wire, Tag tag, ProtoSchema.Field field, ProtoSchema schema,
          ProtoSchema.Message message, List<JsonNode> into) {
        String type = field.kind() == ProtoSchema.Kind.ENUM ? "enum" : field.type();
        int expected = wireTypeOf(type);
        if (tag.wireType() == expected) {
            into.add(scalar(wire, type, field, schema));
        } else if (tag.wireType() == LEN && field.repeated() && expected != LEN) {
            int length = wire.length();
            Wire packed = wire.slice(wire.pos, wire.pos + length);
            while (!packed.atEnd()) into.add(scalar(packed, type, field, schema));
            wire.pos += length;
        } else {
            throw mismatch(tag, field, message);
        }
    }

    private static int wireTypeOf(String type) {
        return switch (type) {
            case "fixed64", "sfixed64", "double" -> I64;
            case "fixed32", "sfixed32", "float" -> I32;
            case "string", "bytes" -> LEN;
            default -> VARINT;   // the integer types, bool and enum
        };
    }

    private static JsonNode scalar(Wire wire, String type, ProtoSchema.Field field, ProtoSchema schema) {
        return switch (type) {
            case "int32" -> NODES.numberNode((int) wire.varint());
            case "int64" -> NODES.numberNode(wire.varint());
            case "uint32" -> NODES.numberNode(wire.varint() & 0xFFFF_FFFFL);
            case "uint64" -> unsigned(wire.varint());
            case "sint32" -> {
                int n = (int) wire.varint();
                yield NODES.numberNode((n >>> 1) ^ -(n & 1));
            }
            case "sint64" -> {
                long n = wire.varint();
                yield NODES.numberNode((n >>> 1) ^ -(n & 1));
            }
            case "bool" -> NODES.booleanNode(wire.varint() != 0);
            case "enum" -> {
                int number = (int) wire.varint();
                String name = schema.enums().get(field.type()).names().get(number);
                yield name != null ? NODES.textNode(name) : NODES.numberNode(number);
            }
            case "fixed32" -> NODES.numberNode(wire.fixed32() & 0xFFFF_FFFFL);
            case "sfixed32" -> NODES.numberNode(wire.fixed32());
            case "float" -> floating(Float.intBitsToFloat(wire.fixed32()), true);
            case "fixed64" -> unsigned(wire.fixed64());
            case "sfixed64" -> NODES.numberNode(wire.fixed64());
            case "double" -> floating(Double.longBitsToDouble(wire.fixed64()), false);
            case "string" -> {
                int length = wire.length();
                String text = utf8(wire.bytes, wire.pos, wire.pos + length);
                if (text == null)
                    throw new MalformedPayload(String.format(Locale.ROOT,
                          "Field %s (number %d) is a string, but its bytes at byte %,d are not UTF-8. "
                                + "Declare it bytes to read them.", field.name(), field.number(),
                          wire.origin(wire.pos)));
                wire.pos += length;
                yield NODES.textNode(text);
            }
            case "bytes" -> {
                int length = wire.length();
                String encoded = base64(wire.bytes, wire.pos, wire.pos + length);
                wire.pos += length;
                yield NODES.textNode(encoded);
            }
            default -> throw new IllegalStateException("Not a scalar type: " + type);
        };
    }

    /** One entry of a map: its key (field 1) and value (field 2), each defaulted when absent. */
    private void mapEntry(Wire entry, ProtoSchema.Field field, ProtoSchema schema, int depth, ObjectNode into) {
        checkDepth(depth + 1, entry);
        JsonNode key = null;
        JsonNode value = null;
        ProtoSchema.Field valueField = field.mapValue();
        while (!entry.atEnd()) {
            Tag tag = tag(entry);
            if (tag.number() == 1) {
                if (tag.wireType() != wireTypeOf(field.mapKey()))
                    throw mismatch(tag, new ProtoSchema.Field("key", "key", 1, false, ProtoSchema.Kind.SCALAR,
                          field.mapKey(), null, null, null, false), mapEntryType(field));
                key = scalar(entry, field.mapKey(), field, null);
            } else if (tag.number() == 2) {
                value = switch (valueField.kind()) {
                    case MESSAGE -> {
                        if (tag.wireType() != LEN) throw mismatch(tag, valueField, mapEntryType(field));
                        int length = entry.length();
                        ObjectNode decoded = typed(entry.slice(entry.pos, entry.pos + length),
                              messageType(schema, valueField.type()), schema, depth + 2, -1);
                        entry.pos += length;
                        yield decoded;
                    }
                    case UNRESOLVED -> rawValue(entry, tag, depth + 1);
                    default -> {
                        String type = valueField.kind() == ProtoSchema.Kind.ENUM ? "enum" : valueField.type();
                        if (tag.wireType() != wireTypeOf(type)) throw mismatch(tag, valueField, mapEntryType(field));
                        yield scalar(entry, type, valueField, schema);
                    }
                };
            } else {
                rawValue(entry, tag, depth + 1);   // not part of a map entry: skipped
            }
        }
        into.set(key == null ? defaultKey(field.mapKey()) : key.asText(),
              value != null ? value : defaultValue(valueField, schema));
    }

    private static ProtoSchema.Message mapEntryType(ProtoSchema.Field field) {
        return new ProtoSchema.Message(field.name() + " entry", Map.of());
    }

    private static String defaultKey(String keyType) {
        return switch (keyType) {
            case "string" -> "";
            case "bool" -> "false";
            default -> "0";
        };
    }

    private static JsonNode defaultValue(ProtoSchema.Field field, ProtoSchema schema) {
        return switch (field.kind()) {
            case MESSAGE, UNRESOLVED, GROUP, MAP -> NODES.objectNode();
            case ENUM -> {
                String zero = schema.enums().get(field.type()).names().get(0);
                yield zero != null ? NODES.textNode(zero) : NODES.numberNode(0);
            }
            case SCALAR -> switch (field.type()) {
                case "string", "bytes" -> NODES.textNode("");
                case "bool" -> NODES.booleanNode(false);
                case "float", "double" -> NODES.numberNode(0.0);
                default -> NODES.numberNode(0);
            };
        };
    }

    // ── Values ────────────────────────────────────────────────────────────

    /** A 64-bit value read as unsigned, exactly. */
    private static JsonNode unsigned(long value) {
        return value >= 0 ? NODES.numberNode(value) : NODES.numberNode(new BigInteger(Long.toUnsignedString(value)));
    }

    /**
     * A float or double as proto3's JSON mapping writes it: NaN and the
     * infinities as strings, a float by its shortest decimal (0.1, not
     * 0.10000000149011612).
     */
    private static JsonNode floating(double value, boolean isFloat) {
        if (Double.isNaN(value)) return NODES.textNode("NaN");
        if (Double.isInfinite(value)) return NODES.textNode(value > 0 ? "Infinity" : "-Infinity");
        return NODES.numberNode(isFloat ? Double.parseDouble(Float.toString((float) value)) : value);
    }

    /** The bytes as UTF-8 text, or null when they are not UTF-8. */
    private static String utf8(byte[] bytes, int start, int end) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }

    private static String base64(byte[] bytes, int start, int end) {
        return Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes, start, end));
    }
}
