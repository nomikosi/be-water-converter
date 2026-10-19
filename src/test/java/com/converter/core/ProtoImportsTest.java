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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Finding a schema's imports on disk")
class ProtoImportsTest {

    @TempDir Path temp;

    private Path write(String path, String text) throws Exception {
        Path file = temp.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test @DisplayName("an import is found under a directory above the schema, its own imports too")
    void foundAboveTheSchema() throws Exception {
        String order = """
              syntax = "proto3";
              package shop.v1;
              import "shop/money.proto";
              import "google/protobuf/timestamp.proto";
              import "missing/thing.proto";
              message Order { shop.Money total = 1; }
              """;
        Path schema = write("proto/shop/v1/order.proto", order);
        write("proto/shop/money.proto", "syntax = \"proto3\";\npackage shop;\nimport public \"shop/currency.proto\";\n"
              + "message Money { Currency currency = 1; int64 units = 2; }\n");
        write("proto/shop/currency.proto", "syntax = \"proto3\";\npackage shop;\nenum Currency { EUR = 0; USD = 1; }\n");

        ProtoImports.Found found = ProtoImports.collect(schema, order, new ProtoConverter());
        assertThat(found.files()).containsOnlyKeys("shop/money.proto", "shop/currency.proto");
        // The well-known types are supplied, not looked for.
        assertThat(found.missing()).containsExactly("missing/thing.proto");

        ProtoSchema read = new ProtoConverter().readSchema(order, found.files());
        assertThat(read.messages().get("shop.Money").fields().get(1).kind()).isEqualTo(ProtoSchema.Kind.ENUM);
        assertThat(read.messages().get("shop.v1.Order").fields().get(1).type()).isEqualTo("shop.Money");
    }

    @Test @DisplayName("a file beside the schema is found, and a cycle of imports ends")
    void besideAndCycles() throws Exception {
        String a = "syntax = \"proto3\";\nimport \"b.proto\";\nmessage A { B b = 1; }\n";
        Path schema = write("a.proto", a);
        write("b.proto", "syntax = \"proto3\";\nimport \"a.proto\";\nmessage B { int32 x = 1; }\n");
        ProtoImports.Found found = ProtoImports.collect(schema, a, new ProtoConverter());
        assertThat(found.files()).containsOnlyKeys("b.proto", "a.proto");
        assertThat(found.missing()).isEmpty();
        assertThat(new ProtoConverter().readSchema(a, found.files()).messages().get("A").fields().get(1).kind())
              .isEqualTo(ProtoSchema.Kind.MESSAGE);
    }
}
