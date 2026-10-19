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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Protobuf's well-known types: the messages {@code google/protobuf/*.proto}
 * declares that protobuf's JSON printer writes in forms of their own. A
 * Timestamp is an RFC 3339 string, a Duration {@code "1.5s"}, a wrapper the
 * value it wraps, a Struct, Value or ListValue the JSON it holds, a FieldMask
 * its paths in lowerCamelCase joined by commas, and an Any its message with
 * {@code @type} beside its fields.
 *
 * <p>The files are supplied for an import of them, so a schema needs only
 * name them. They are written out here, the messages and field numbers
 * protobuf declares, without the options and comments of protobuf's copies.
 */
final class WellKnownTypes {

    private WellKnownTypes() {}

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    static final String TIMESTAMP = "google.protobuf.Timestamp";
    static final String DURATION = "google.protobuf.Duration";
    static final String STRUCT = "google.protobuf.Struct";
    static final String VALUE = "google.protobuf.Value";
    static final String LIST_VALUE = "google.protobuf.ListValue";
    static final String NULL_VALUE = "google.protobuf.NullValue";
    static final String FIELD_MASK = "google.protobuf.FieldMask";
    static final String ANY = "google.protobuf.Any";

    /** The wrappers, each holding one value in its field 1. */
    private static final List<String> WRAPPERS = List.of("DoubleValue", "FloatValue", "Int64Value", "UInt64Value",
          "Int32Value", "UInt32Value", "BoolValue", "StringValue", "BytesValue");

    private static final String HEADER = "syntax = \"proto3\";\n\npackage google.protobuf;\n\n";

    /** Each file by the path an import names it by. */
    static final Map<String, String> PROTO_FILES = Map.of(
          "google/protobuf/timestamp.proto", HEADER + "message Timestamp {\n  int64 seconds = 1;\n  int32 nanos = 2;\n}\n",
          "google/protobuf/duration.proto", HEADER + "message Duration {\n  int64 seconds = 1;\n  int32 nanos = 2;\n}\n",
          "google/protobuf/wrappers.proto", HEADER
                + "message DoubleValue { double value = 1; }\nmessage FloatValue { float value = 1; }\n"
                + "message Int64Value { int64 value = 1; }\nmessage UInt64Value { uint64 value = 1; }\n"
                + "message Int32Value { int32 value = 1; }\nmessage UInt32Value { uint32 value = 1; }\n"
                + "message BoolValue { bool value = 1; }\nmessage StringValue { string value = 1; }\n"
                + "message BytesValue { bytes value = 1; }\n",
          "google/protobuf/struct.proto", HEADER
                + "message Struct {\n  map<string, Value> fields = 1;\n}\n\n"
                + "message Value {\n  oneof kind {\n    NullValue null_value = 1;\n    double number_value = 2;\n"
                + "    string string_value = 3;\n    bool bool_value = 4;\n    Struct struct_value = 5;\n"
                + "    ListValue list_value = 6;\n  }\n}\n\n"
                + "enum NullValue {\n  NULL_VALUE = 0;\n}\n\n"
                + "message ListValue {\n  repeated Value values = 1;\n}\n",
          "google/protobuf/empty.proto", HEADER + "message Empty {}\n",
          "google/protobuf/field_mask.proto", HEADER + "message FieldMask {\n  repeated string paths = 1;\n}\n",
          "google/protobuf/any.proto", HEADER + "message Any {\n  string type_url = 1;\n  bytes value = 2;\n}\n");

    /** Seconds from 0001-01-01T00:00:00Z to 9999-12-31T23:59:59Z, the span a Timestamp may hold. */
    private static final long MIN_SECONDS = -62_135_596_800L;
    private static final long MAX_SECONDS = 253_402_300_799L;
    /** Ten thousand years, the span a Duration may hold either way. */
    private static final long MAX_DURATION_SECONDS = 315_576_000_000L;

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

    /**
     * A decoded message as protobuf's JSON printer writes it, when its type is
     * one with a form of its own; null when it is not. Any is the decoder's, as
     * it needs the schema.
     *
     * @param decoded the message's fields as the decoder wrote them: by name,
     *                and without those that hold their default
     */
    static JsonNode json(String type, ObjectNode decoded) {
        if (!type.startsWith("google.protobuf.")) return null;
        String simple = type.substring("google.protobuf.".length());
        if (WRAPPERS.contains(simple)) {
            JsonNode value = decoded.get("value");
            if (value != null) return value;
            return switch (simple) {
                case "BoolValue" -> NODES.booleanNode(false);
                case "StringValue", "BytesValue" -> NODES.textNode("");
                case "DoubleValue", "FloatValue" -> NODES.numberNode(0.0);
                default -> NODES.numberNode(0);
            };
        }
        return switch (type) {
            case TIMESTAMP -> NODES.textNode(timestamp(seconds(decoded), nanos(decoded)));
            case DURATION -> NODES.textNode(duration(seconds(decoded), nanos(decoded)));
            case STRUCT -> decoded.has("fields") ? decoded.get("fields") : NODES.objectNode();
            case LIST_VALUE -> decoded.has("values") ? decoded.get("values") : NODES.arrayNode();
            case VALUE -> value(decoded);
            case FIELD_MASK -> NODES.textNode(fieldMask(decoded.get("paths")));
            default -> null;
        };
    }

    private static long seconds(ObjectNode decoded) {
        return decoded.has("seconds") ? decoded.get("seconds").longValue() : 0;
    }

    private static int nanos(ObjectNode decoded) {
        return decoded.has("nanos") ? decoded.get("nanos").intValue() : 0;
    }

    /** As protobuf writes it: UTC, and a fraction of three, six or nine digits, or none. */
    static String timestamp(long seconds, int nanos) {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS || nanos < 0 || nanos > 999_999_999)
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                  "The Timestamp holds %d seconds and %d nanos, which is no time between the years 1 and 9999 "
                        + "that protobuf can write.", seconds, nanos));
        return SECONDS.format(Instant.ofEpochSecond(seconds)) + fraction(nanos) + "Z";
    }

    /** As protobuf writes it: seconds, a fraction of three, six or nine digits, and an s. */
    static String duration(long seconds, int nanos) {
        if (Math.abs(seconds) > MAX_DURATION_SECONDS || Math.abs(nanos) > 999_999_999
              || seconds < 0 && nanos > 0 || seconds > 0 && nanos < 0)
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                  "The Duration holds %d seconds and %d nanos, which protobuf cannot write: it spans at most "
                        + "10,000 years, and both parts have the same sign.", seconds, nanos));
        boolean negative = seconds < 0 || nanos < 0;
        return (negative ? "-" : "") + Math.abs(seconds) + fraction(Math.abs(nanos)) + "s";
    }

    private static String fraction(int nanos) {
        if (nanos == 0) return "";
        if (nanos % 1_000_000 == 0) return String.format(Locale.ROOT, ".%03d", nanos / 1_000_000);
        if (nanos % 1_000 == 0) return String.format(Locale.ROOT, ".%06d", nanos / 1_000);
        return String.format(Locale.ROOT, ".%09d", nanos);
    }

    /** The JSON a Value holds; null when it holds null, or nothing. */
    private static JsonNode value(ObjectNode decoded) {
        for (String kind : List.of("number_value", "string_value", "bool_value", "struct_value", "list_value")) {
            JsonNode held = decoded.get(kind);
            if (held != null) return held;
        }
        return NODES.nullNode();
    }

    /** Each path in lowerCamelCase, as protobuf writes a mask, joined by commas. */
    private static String fieldMask(JsonNode paths) {
        if (paths == null) return "";
        List<String> written = new ArrayList<>();
        for (JsonNode path : (ArrayNode) paths) {
            StringBuilder camel = new StringBuilder();
            boolean upper = false;
            for (char c : path.asText().toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    camel.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            written.add(camel.toString());
        }
        return String.join(",", written);
    }
}
