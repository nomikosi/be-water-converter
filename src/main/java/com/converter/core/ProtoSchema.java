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

import java.util.List;
import java.util.Map;

/**
 * A {@code .proto} schema as a payload is decoded against it: each message's
 * fields by number, with their types resolved the way
 * {@link ProtoConverter#protoToJson} resolves them, and each enum's names by
 * number. Built by {@link ProtoConverter#readSchema}.
 *
 * @param messages every message type, groups included, by fully qualified
 *                 name in declaration order
 * @param enums    every enum, by fully qualified name
 */
public record ProtoSchema(Map<String, Message> messages, Map<String, EnumType> enums) {

    public ProtoSchema {
        messages = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(messages));
        enums = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(enums));
    }

    /** The message types a payload can hold, fully qualified, in declaration order. */
    public List<String> messageNames() {
        return List.copyOf(messages.keySet());
    }

    /**
     * @param fields by field number, in declaration order
     */
    public record Message(String fullName, Map<Integer, Field> fields) {
        public Message {
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }
    }

    /** What a field holds. */
    public enum Kind {
        /** One of protobuf's scalar types, named by {@link Field#type()}. */
        SCALAR,
        /** An enum of this schema, by its full name. */
        ENUM,
        /** A message of this schema, by its full name. */
        MESSAGE,
        /** A proto2 group: a message of this schema, written between start and end tags. */
        GROUP,
        /** A {@code map<K, V>}: key type in {@link Field#mapKey()}, value in {@link Field#mapValue()}. */
        MAP,
        /** A message from another file, which this schema does not hold: decoded raw. */
        UNRESOLVED
    }

    /**
     * @param name             the field's name in the schema
     * @param jsonKey          the key it is written under: its {@code json_name}, else its name
     * @param type             the scalar type, the full name of the enum or message, or
     *                         the type as written when it is unresolved
     * @param mapKey           for a map, the key's scalar type
     * @param mapValue         for a map, the value, as field 2 of the map entry
     * @param oneof            the oneof the field is a member of, or null: of a oneof's
     *                         members, only the one a payload sets last is kept
     * @param implicitPresence a singular scalar or enum whose default value cannot be
     *                         told apart from no value: proto3's, unless marked
     *                         {@code optional} or in a oneof, and an editions field with
     *                         {@code features.field_presence = IMPLICIT}. JSON leaves its
     *                         default out.
     */
    public record Field(String name, String jsonKey, int number, boolean repeated, Kind kind, String type,
                        String mapKey, Field mapValue, String oneof, boolean implicitPresence) {}

    /**
     * @param names by number; with {@code allow_alias}, the first name declared
     */
    public record EnumType(String fullName, Map<Integer, String> names) {
        public EnumType {
            names = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(names));
        }
    }
}
