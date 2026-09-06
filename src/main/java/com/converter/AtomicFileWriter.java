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

package com.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.*;

/** Replaces a file using a temporary file owned exclusively by this write. */
final class AtomicFileWriter {
    private AtomicFileWriter() {}

    static void write(Path target, String text) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), ".bewater-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            replace(temp, absolute);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // Windows can reject simultaneous replacements of the same destination.
    // Serialize only the rename step; writing the independent temp files can
    // proceed concurrently, and no per-path lock registry needs to be retained.
    private static synchronized void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomicHere) {
            Files.move(temp, target, REPLACE_EXISTING);
        }
    }
}
