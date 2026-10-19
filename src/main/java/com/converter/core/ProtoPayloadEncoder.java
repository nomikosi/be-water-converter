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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes JSON as a binary Protobuf message of a schema's type: what
 * {@link ProtoPayloadDecoder} reads, the other way.
 *
 * <p>The JSON is read as protobuf's own JSON parser reads it: a field under its
 * name or its JSON name, 64-bit integers as numbers or strings, a whole number
 * written as a decimal ({@code 1.0}) taken as the integer it is, enums by name
 * or number, bytes in base64, NaN and the infinities as strings, null for a
 * field not set, and the well-known types in their forms. A key the message
 * has no field for, a value out of its type's range, and two members of one
 * oneof are refused, each with the path to it. The message is written as
 * protobuf writes it: fields in number order, a field without presence left
 * out when it holds its default, and repeated numbers packed, which every
 * protobuf parser reads.
 */
public final class ProtoPayloadEncoder {

    private static final int VARINT = 0;
    private static final int I64 = 1;
    private static final int LEN = 2;
    private static final int SGROUP = 3;
    private static final int EGROUP = 4;
    private static final int I32 = 5;

    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final ProtoConverter proto;
    private String cachedSchemaText;
    private Map<String, String> cachedImports;
    private ProtoSchema cachedSchema;

    public ProtoPayloadEncoder(ProtoConverter proto) {
        this.proto = proto;
    }

    /**
     * @param base64 the payload in base64; otherwise in hex
     */
    public String encode(String json, String schemaText, String messageType, Map<String, String> imports,
          boolean base64) throws Exception {
        if (schemaText == null || schemaText.isBlank() || messageType == null || messageType.isBlank())
            throw new IllegalArgumentException("Writing a Protobuf payload takes its schema and message type: "
                  + "choose the .proto file and the message in the options bar.");
        ProtoSchema schema = schema(schemaText, imports);
        ProtoSchema.Message message = schema.messages().get(messageType);
        if (message == null)
            throw new IllegalArgumentException("The schema declares no message " + messageType
                  + ". It declares " + String.join(", ", schema.messageNames()) + ".");
        JsonNode root = LenientJson.read(LenientJson.COMPACT, json);
        Out out = new Out();
        message(root, message, schema, out, "$", 0);
        byte[] bytes = out.toByteArray();
        return base64 ? Base64.getEncoder().encodeToString(bytes) : HexFormat.of().formatHex(bytes);
    }

    private synchronized ProtoSchema schema(String schemaText, Map<String, String> imports) {
        if (!schemaText.equals(cachedSchemaText) || !imports.equals(cachedImports)) {
            cachedSchema = proto.readSchema(schemaText, imports);
            cachedSchemaText = schemaText;
            cachedImports = imports;
        }
        return cachedSchema;
    }

    // ── Messages ──────────────────────────────────────────────────────────

    /** A message's fields, from its JSON, written to {@code out}. */
    private void message(JsonNode json, ProtoSchema.Message type, ProtoSchema schema, Out out, String path, int depth) {
        if (depth > ProtoPayloadDecoder.MAX_DEPTH)
            throw refused(path, "nests messages more than " + ProtoPayloadDecoder.MAX_DEPTH + " deep");
        if (wellKnown(json, type, schema, out, path, depth)) return;
        if (!json.isObject())
            throw refused(path, "is " + kind(json) + ", where the message " + type.fullName() + " takes an object");
        TreeMap<Integer, JsonNode> set = new TreeMap<>();
        Map<Integer, String> keys = new java.util.HashMap<>();
        Map<String, String> oneofs = new java.util.HashMap<>();
        for (Map.Entry<String, JsonNode> entry : json.properties()) {
            ProtoSchema.Field field = fieldFor(type, entry.getKey());
            if (field == null)
                throw refused(path, "has the key " + TypeScriptGenerator.stringLiteral(entry.getKey())
                      + ", which the message " + type.fullName() + " has no field for");
            String earlier = keys.put(field.number(), entry.getKey());
            if (earlier != null)
                throw refused(path, "sets the field " + field.name() + " twice, as " + earlier + " and "
                      + entry.getKey());
            JsonNode value = entry.getValue();
            if (value.isNull() && !takesNull(field)) continue;
            if (field.oneof() != null) {
                String other = oneofs.put(field.oneof(), field.name());
                if (other != null)
                    throw refused(path, "sets both " + other + " and " + field.name()
                          + ", members of the oneof " + field.oneof() + ", which holds one");
            }
            set.put(field.number(), value);
        }
        for (Map.Entry<Integer, JsonNode> entry : set.entrySet()) {
            ProtoSchema.Field field = type.fields().get(entry.getKey());
            field(entry.getValue(), field, schema, out, path + "." + keys.get(entry.getKey()), depth);
        }
    }

    /** The field a key names: its name, its JSON name, or the lowerCamelCase name protobuf gives it. */
    private static ProtoSchema.Field fieldFor(ProtoSchema.Message type, String key) {
        for (ProtoSchema.Field field : type.fields().values())
            if (field.name().equals(key) || field.jsonKey().equals(key) || lowerCamel(field.name()).equals(key))
                return field;
        return null;
    }

    /** A Value, and NullValue, are the two types JSON's null stands for a value of. */
    private static boolean takesNull(ProtoSchema.Field field) {
        String type = field.kind() == ProtoSchema.Kind.MAP ? null : field.type();
        return !field.repeated() && (WellKnownTypes.VALUE.equals(type) || WellKnownTypes.NULL_VALUE.equals(type));
    }

    private void field(JsonNode json, ProtoSchema.Field field, ProtoSchema schema, Out out, String path, int depth) {
        if (field.kind() == ProtoSchema.Kind.UNRESOLVED)
            throw refused(path, "is of the type " + field.type() + ", which is in no file of the schema at hand");
        if (field.kind() == ProtoSchema.Kind.MAP) {
            if (!json.isObject()) throw refused(path, "is " + kind(json) + ", where a map takes an object");
            ProtoSchema.Field key = new ProtoSchema.Field("key", "key", 1, false, ProtoSchema.Kind.SCALAR, field.mapKey(),
                  null, null, null, false);
            for (Map.Entry<String, JsonNode> entry : json.properties()) {
                String at = path + "[" + TypeScriptGenerator.stringLiteral(entry.getKey()) + "]";
                if (entry.getValue().isNull() && !WellKnownTypes.VALUE.equals(field.mapValue().type()))
                    throw refused(at, "is null, which a map cannot hold");
                Out pair = new Out();
                single(mapKey(entry.getKey(), field.mapKey(), at), key, schema, pair, at, depth, true);
                single(entry.getValue(), field.mapValue(), schema, pair, at, depth, true);
                out.tag(field.number(), LEN);
                out.lengthDelimited(pair.toByteArray());
            }
            return;
        }
        if (field.repeated()) {
            if (!json.isArray()) throw refused(path, "is " + kind(json) + ", where a repeated field takes a list");
            boolean packed = (field.kind() == ProtoSchema.Kind.SCALAR || field.kind() == ProtoSchema.Kind.ENUM)
                  && wireType(field) != LEN;
            Out run = packed ? new Out() : out;
            for (int i = 0; i < json.size(); i++) {
                JsonNode element = json.get(i);
                String at = path + "[" + i + "]";
                if (element.isNull() && !WellKnownTypes.VALUE.equals(field.type()))
                    throw refused(at, "is null, which a list cannot hold");
                if (packed) scalarValue(element, field, schema, run, at);
                else single(element, field, schema, out, at, depth, true);
            }
            if (packed && json.size() > 0) {
                out.tag(field.number(), LEN);
                out.lengthDelimited(run.toByteArray());
            }
            return;
        }
        single(json, field, schema, out, path, depth, !field.implicitPresence());
    }

    /**
     * One value of a field, with its tag.
     *
     * @param always written even when it holds its default, as a field with presence is
     */
    private void single(JsonNode json, ProtoSchema.Field field, ProtoSchema schema, Out out, String path, int depth,
          boolean always) {
        switch (field.kind()) {
            case MESSAGE -> {
                Out body = new Out();
                message(json, messageType(schema, field.type()), schema, body, path, depth + 1);
                out.tag(field.number(), LEN);
                out.lengthDelimited(body.toByteArray());
            }
            case GROUP -> {
                out.tag(field.number(), SGROUP);
                message(json, messageType(schema, field.type()), schema, out, path, depth + 1);
                out.tag(field.number(), EGROUP);
            }
            case SCALAR, ENUM -> {
                Out value = new Out();
                boolean isDefault = scalarValue(json, field, schema, value, path);
                if (isDefault && !always) return;
                out.tag(field.number(), wireType(field));
                out.writeBytes(value.toByteArray());
            }
            default -> throw refused(path, "is of the type " + field.type() + ", which is in no file of the schema at hand");
        }
    }

    private static ProtoSchema.Message messageType(ProtoSchema schema, String fullName) {
        ProtoSchema.Message type = schema.messages().get(fullName);
        if (type == null) throw new IllegalStateException("No message " + fullName + " in the schema");
        return type;
    }

    private static int wireType(ProtoSchema.Field field) {
        if (field.kind() == ProtoSchema.Kind.ENUM) return VARINT;
        return switch (field.type()) {
            case "fixed64", "sfixed64", "double" -> I64;
            case "fixed32", "sfixed32", "float" -> I32;
            case "string", "bytes" -> LEN;
            default -> VARINT;
        };
    }

    // ── Scalars ───────────────────────────────────────────────────────────

    /** A map key, which JSON writes as a string, as the value of its key type. */
    private static JsonNode mapKey(String key, String type, String path) {
        if (type.equals("string")) return com.fasterxml.jackson.databind.node.TextNode.valueOf(key);
        if (type.equals("bool")) {
            if (!key.equals("true") && !key.equals("false"))
                throw refused(path, "has the key " + key + ", where a map's bool key is true or false");
            return com.fasterxml.jackson.databind.node.BooleanNode.valueOf(key.equals("true"));
        }
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(key);
    }

    /**
     * A scalar's or enum's value, without its tag.
     *
     * @return whether it is the field's default
     */
    private static boolean scalarValue(JsonNode json, ProtoSchema.Field field, ProtoSchema schema, Out out, String path) {
        if (field.kind() == ProtoSchema.Kind.ENUM) {
            int number = enumNumber(json, field, schema, path);
            out.varint(number);
            return number == 0;
        }
        switch (field.type()) {
            case "int32" -> {
                long value = integer(json, path, "an int32", Integer.MIN_VALUE, Integer.MAX_VALUE).longValue();
                out.varint(value);
                return value == 0;
            }
            case "sint32" -> {
                int value = integer(json, path, "an sint32", Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
                out.varint(((long) value << 1 ^ value >> 31) & 0xFFFF_FFFFL);
                return value == 0;
            }
            case "sfixed32" -> {
                int value = integer(json, path, "an sfixed32", Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
                out.fixed32(value);
                return value == 0;
            }
            case "uint32" -> {
                long value = integer(json, path, "a uint32", 0, 0xFFFF_FFFFL).longValue();
                out.varint(value);
                return value == 0;
            }
            case "fixed32" -> {
                long value = integer(json, path, "a fixed32", 0, 0xFFFF_FFFFL).longValue();
                out.fixed32((int) value);
                return value == 0;
            }
            case "int64", "sint64", "sfixed64" -> {
                long value = integer(json, path, "an " + field.type(), Long.MIN_VALUE, Long.MAX_VALUE).longValue();
                switch (field.type()) {
                    case "int64" -> out.varint(value);
                    case "sint64" -> out.varint(value << 1 ^ value >> 63);
                    default -> out.fixed64(value);
                }
                return value == 0;
            }
            case "uint64", "fixed64" -> {
                BigInteger value = integer(json, path, "a " + field.type(), BigInteger.ZERO, UINT64_MAX);
                if (field.type().equals("uint64")) out.varint(value.longValue());
                else out.fixed64(value.longValue());
                return value.signum() == 0;
            }
            case "bool" -> {
                boolean value;
                if (json.isBoolean()) value = json.booleanValue();
                else if (json.isTextual() && (json.asText().equals("true") || json.asText().equals("false")))
                    value = json.asText().equals("true");
                else throw refused(path, "is " + kind(json) + ", where a bool is true or false");
                out.varint(value ? 1 : 0);
                return !value;
            }
            case "float" -> {
                double value = floating(json, path, true);
                out.fixed32(Float.floatToIntBits((float) value));
                return Float.floatToIntBits((float) value) == 0;
            }
            case "double" -> {
                double value = floating(json, path, false);
                out.fixed64(Double.doubleToLongBits(value));
                return Double.doubleToLongBits(value) == 0;
            }
            case "string" -> {
                if (!json.isValueNode() || json.isNull())
                    throw refused(path, "is " + kind(json) + ", where a string is text");
                byte[] bytes = json.asText().getBytes(StandardCharsets.UTF_8);
                out.lengthDelimited(bytes);
                return bytes.length == 0;
            }
            case "bytes" -> {
                if (!json.isTextual()) throw refused(path, "is " + kind(json) + ", where bytes are base64 text");
                byte[] bytes = base64(json.asText(), path);
                out.lengthDelimited(bytes);
                return bytes.length == 0;
            }
            default -> throw new IllegalStateException("Not a scalar type: " + field.type());
        }
    }

    private static int enumNumber(JsonNode json, ProtoSchema.Field field, ProtoSchema schema, String path) {
        if (WellKnownTypes.NULL_VALUE.equals(field.type()) && (json.isNull() || json.asText().equals("NULL_VALUE")))
            return 0;
        ProtoSchema.EnumType type = schema.enums().get(field.type());
        if (json.isTextual()) {
            Integer number = type.numbers().get(json.asText());
            if (number != null) return number;
            throw refused(path, "is " + json.asText() + ", which the enum " + field.type() + " has no value named");
        }
        return integer(json, path, "an enum value", Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
    }

    /** A whole number, from a JSON number or a string holding one, as protobuf's JSON parser takes both. */
    private static BigInteger integer(JsonNode json, String path, String what, long min, long max) {
        return integer(json, path, what, BigInteger.valueOf(min), BigInteger.valueOf(max));
    }

    private static BigInteger integer(JsonNode json, String path, String what, BigInteger min, BigInteger max) {
        BigInteger value;
        try {
            if (!json.isNumber() && !json.isTextual()) throw new NumberFormatException();
            // 1.0 and 1e2 are whole numbers too.
            value = new BigDecimal(json.isNumber() ? json.decimalValue().toString() : json.asText().trim())
                  .toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException notWhole) {
            throw refused(path, "is " + kind(json) + ", where " + what + " is a whole number");
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0)
            throw refused(path, "is " + value + ", outside the range of " + what + ", " + min + " to " + max);
        return value;
    }

    private static double floating(JsonNode json, String path, boolean isFloat) {
        String what = isFloat ? "a float" : "a double";
        if (json.isTextual()) {
            switch (json.asText()) {
                case "NaN" -> { return Double.NaN; }
                case "Infinity" -> { return Double.POSITIVE_INFINITY; }
                case "-Infinity" -> { return Double.NEGATIVE_INFINITY; }
                default -> { }
            }
        }
        double value;
        try {
            if (!json.isNumber() && !json.isTextual()) throw new NumberFormatException();
            value = json.isNumber() ? json.doubleValue() : Double.parseDouble(json.asText().trim());
        } catch (NumberFormatException notANumber) {
            throw refused(path, "is " + kind(json) + ", where " + what + " is a number, NaN or Infinity");
        }
        if (Double.isInfinite(value) || isFloat && Math.abs(value) > Float.MAX_VALUE * (1 + 1e-6))
            throw refused(path, "is " + json.asText() + ", outside the range of " + what);
        return value;
    }

    /** Base64, standard or URL-safe, padded or not, as protobuf's JSON parser takes it. */
    private static byte[] base64(String text, String path) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(text);
            } catch (IllegalArgumentException notBase64) {
                throw refused(path, "is not base64, which bytes are written in");
            }
        }
    }

    // ── Well-known types ──────────────────────────────────────────────────

    private static final Pattern TIMESTAMP = Pattern.compile(
          "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern DURATION = Pattern.compile("(-)?(\\d+)(?:\\.(\\d{1,9}))?s");

    /**
     * Writes a well-known type from its own JSON form; false when the type is
     * none, or has no form but an object of its fields.
     */
    private boolean wellKnown(JsonNode json, ProtoSchema.Message type, ProtoSchema schema, Out out, String path,
          int depth) {
        String name = type.fullName();
        if (!name.startsWith("google.protobuf.")) return false;
        switch (name) {
            case WellKnownTypes.TIMESTAMP -> {
                long[] parts = timestamp(json, path);
                secondsAndNanos(out, parts[0], (int) parts[1]);
            }
            case WellKnownTypes.DURATION -> {
                long[] parts = duration(json, path);
                secondsAndNanos(out, parts[0], (int) parts[1]);
            }
            case WellKnownTypes.STRUCT -> {
                if (!json.isObject()) throw refused(path, "is " + kind(json) + ", where a Struct takes an object");
                field(json, type.fields().get(1), schema, out, path, depth);
            }
            case WellKnownTypes.LIST_VALUE -> {
                if (!json.isArray()) throw refused(path, "is " + kind(json) + ", where a ListValue takes a list");
                field(json, type.fields().get(1), schema, out, path, depth);
            }
            case WellKnownTypes.VALUE -> {
                int number = json.isNull() ? 1 : json.isNumber() ? 2 : json.isTextual() ? 3 : json.isBoolean() ? 4
                      : json.isObject() ? 5 : 6;
                single(json.isNull() ? com.fasterxml.jackson.databind.node.IntNode.valueOf(0) : json,
                      type.fields().get(number), schema, out, path, depth, true);
            }
            case WellKnownTypes.FIELD_MASK -> {
                if (!json.isTextual()) throw refused(path, "is " + kind(json) + ", where a FieldMask is text");
                for (String mask : json.asText().split(",")) {
                    if (mask.isEmpty()) continue;
                    out.tag(1, LEN);
                    out.lengthDelimited(snake(mask).getBytes(StandardCharsets.UTF_8));
                }
            }
            case WellKnownTypes.ANY -> any(json, schema, out, path, depth);
            default -> {
                String simple = name.substring("google.protobuf.".length());
                if (!simple.endsWith("Value") || !type.fields().containsKey(1) || type.fields().size() != 1) return false;
                // A wrapper: the value itself, left out when it is the default.
                single(json, type.fields().get(1), schema, out, path, depth, false);
            }
        }
        return true;
    }

    private static void secondsAndNanos(Out out, long seconds, int nanos) {
        if (seconds != 0) {
            out.tag(1, VARINT);
            out.varint(seconds);
        }
        if (nanos != 0) {
            out.tag(2, VARINT);
            out.varint(nanos);
        }
    }

    /** {@code 1972-01-01T10:00:20.021Z}, or with an offset, as protobuf reads a Timestamp. */
    static long[] timestamp(JsonNode json, String path) {
        Matcher m = json.isTextual() ? TIMESTAMP.matcher(json.asText()) : null;
        if (m == null || !m.matches())
            throw refused(path, "is " + kind(json) + ", where a Timestamp is RFC 3339 text such as 1972-01-01T10:00:20.021Z");
        long seconds;
        try {
            LocalDateTime local = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                  Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                  Integer.parseInt(m.group(6)));
            ZoneOffset offset = m.group(8).equals("Z") ? ZoneOffset.UTC : ZoneOffset.of(m.group(8));
            seconds = local.toEpochSecond(offset);
        } catch (java.time.DateTimeException notADate) {
            throw refused(path, "is " + json.asText() + ", which is no date and time");
        }
        int nanos = m.group(7) == null ? 0 : Integer.parseInt((m.group(7) + "00000000").substring(0, 9));
        // The span protobuf allows, checked as it writes one back.
        WellKnownTypes.timestamp(seconds, nanos);
        return new long[]{seconds, nanos};
    }

    /** {@code 1.5s}, {@code -0.25s}, as protobuf reads a Duration. */
    static long[] duration(JsonNode json, String path) {
        Matcher m = json.isTextual() ? DURATION.matcher(json.asText()) : null;
        if (m == null || !m.matches())
            throw refused(path, "is " + kind(json) + ", where a Duration is seconds and an s, such as 1.5s");
        long seconds;
        try {
            seconds = Long.parseLong(m.group(2));
        } catch (NumberFormatException tooLong) {
            throw refused(path, "is " + json.asText() + ", longer than a Duration holds");
        }
        int nanos = m.group(3) == null ? 0 : Integer.parseInt((m.group(3) + "00000000").substring(0, 9));
        if (m.group(1) != null) {
            seconds = -seconds;
            nanos = -nanos;
        }
        WellKnownTypes.duration(seconds, nanos);
        return new long[]{seconds, nanos};
    }

    private void any(JsonNode json, ProtoSchema schema, Out out, String path, int depth) {
        if (!json.isObject()) throw refused(path, "is " + kind(json) + ", where an Any takes an object");
        if (json.isEmpty()) return;
        JsonNode typeUrl = json.get("@type");
        if (typeUrl == null || !typeUrl.isTextual())
            throw refused(path, "is an Any without @type, which names the message it holds");
        String url = typeUrl.asText();
        ProtoSchema.Message held = schema.messages().get(url.substring(url.lastIndexOf('/') + 1));
        if (held == null)
            throw refused(path, "holds " + url + ", a message the schema at hand does not declare");
        Out body = new Out();
        com.fasterxml.jackson.databind.node.ObjectNode fields = ((com.fasterxml.jackson.databind.node.ObjectNode) json)
              .deepCopy();
        fields.remove("@type");
        boolean ownForm = WellKnownTypes.ANY.equals(held.fullName())
              || WellKnownTypes.json(held.fullName(), com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode()) != null;
        message(ownForm ? fields.path("value") : fields, held, schema, body, path, depth + 1);
        out.tag(1, LEN);
        out.lengthDelimited(url.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = body.toByteArray();
        if (bytes.length > 0) {
            out.tag(2, LEN);
            out.lengthDelimited(bytes);
        }
    }

    /** {@code fooBar} as the path {@code foo_bar}, as protobuf reads a FieldMask. */
    private static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (c >= 'A' && c <= 'Z') out.append('_').append((char) (c + ('a' - 'A')));
            else out.append(c);
        }
        return out.toString();
    }

    /** {@code foo_bar} as {@code fooBar}: the JSON name protobuf gives a field without one of its own. */
    static String lowerCamel(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    // ── Writing ───────────────────────────────────────────────────────────

    private static IllegalArgumentException refused(String path, String problem) {
        return new IllegalArgumentException(path + " " + problem + ".");
    }

    private static String kind(JsonNode json) {
        if (json.isMissingNode() || json.isNull()) return "null";
        if (json.isObject()) return "an object";
        if (json.isArray()) return "a list";
        if (json.isTextual()) return TypeScriptGenerator.stringLiteral(json.asText());
        return json.toString();
    }

    /** The wire format, written. */
    private static final class Out extends ByteArrayOutputStream {
        void varint(long value) {
            while ((value & ~0x7FL) != 0) {
                write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            write((int) value);
        }

        void tag(int number, int wireType) {
            varint((long) number << 3 | wireType);
        }

        void fixed32(int value) {
            for (int i = 0; i < 4; i++) write(value >>> 8 * i);
        }

        void fixed64(long value) {
            for (int i = 0; i < 8; i++) write((int) (value >>> 8 * i));
        }

        void lengthDelimited(byte[] bytes) {
            varint(bytes.length);
            write(bytes, 0, bytes.length);
        }
    }
}
