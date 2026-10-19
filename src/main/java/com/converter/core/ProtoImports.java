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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files a {@code .proto} schema imports, found on disk beside it.
 *
 * <p>protoc resolves an import against the roots it is given with
 * {@code -I}; a schema chosen on its own comes with none. Its own directory and
 * each one above it are tried in turn, which finds
 * {@code import "shop/v1/money.proto";} from {@code proto/shop/v1/order.proto}
 * under {@code proto/}, the layout protobuf projects use. Imports of imports
 * are followed too, and the well-known types are left to
 * {@link WellKnownTypes}, which supplies them.
 */
public final class ProtoImports {

    private ProtoImports() {}

    /** How many directories above the schema's own are tried as roots. */
    static final int ROOTS_ABOVE = 8;

    /**
     * @param files   each file found, by the path its import statement names
     * @param missing the paths no root holds: their types decode raw
     */
    public record Found(Map<String, String> files, List<String> missing) {}

    public static Found collect(Path schemaFile, String schemaText, ProtoConverter proto) {
        List<Path> roots = new ArrayList<>();
        Path directory = schemaFile.toAbsolutePath().getParent();
        for (int i = 0; directory != null && i <= ROOTS_ABOVE; i++, directory = directory.getParent())
            roots.add(directory);
        Map<String, String> files = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        ArrayDeque<String> pending = new ArrayDeque<>(importsOf(schemaText, proto));
        while (!pending.isEmpty()) {
            String path = pending.poll();
            if (files.containsKey(path) || missing.contains(path) || WellKnownTypes.PROTO_FILES.containsKey(path))
                continue;
            String text = read(roots, path);
            if (text == null) {
                missing.add(path);
                continue;
            }
            files.put(path, text);
            pending.addAll(importsOf(text, proto));
        }
        return new Found(files, missing);
    }

    private static List<String> importsOf(String text, ProtoConverter proto) {
        try {
            return proto.importPaths(text);
        } catch (IllegalArgumentException unreadable) {
            // Reported when the schema is read; what it imports is unknown.
            return List.of();
        }
    }

    private static String read(List<Path> roots, String importPath) {
        for (Path root : roots) {
            try {
                Path candidate = root.resolve(importPath).normalize();
                if (Files.isRegularFile(candidate))
                    return TextDecoder.decode(Files.readAllBytes(candidate), StandardCharsets.UTF_8).text();
            } catch (InvalidPathException | IOException unusable) {
                // Not a file this root holds; the next may.
            }
        }
        return null;
    }
}
