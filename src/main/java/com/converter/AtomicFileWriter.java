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
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.SecureRandom;

import static java.nio.file.StandardCopyOption.*;

/** Replaces a file using a temporary file owned exclusively by this write. */
final class AtomicFileWriter {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AtomicFileWriter() {}

    static void write(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    static void write(Path target, byte[] content) throws IOException {
        // Through a symbolic link to the file it names: renaming onto the link
        // replaced the link itself with a plain file, and the file it pointed
        // at kept the old text.
        Path destination = followLinks(target.toAbsolutePath());
        Path temp = createTemp(destination.getParent());
        try {
            Files.write(temp, content);
            keepPermissions(destination, temp);
            replace(temp, destination);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path followLinks(Path path) throws IOException {
        for (int hops = 0; Files.isSymbolicLink(path); hops++) {
            if (hops == 40) throw new IOException("Too many levels of symbolic links: " + path);
            path = path.resolveSibling(Files.readSymbolicLink(path)).normalize();
        }
        return path;
    }

    /**
     * A temporary file with the directory's ordinary permissions.
     * Files.createTempFile makes it owner-only on Linux and macOS, and the
     * rename then handed rw------- to the saved file: every save made the file
     * unreadable to everyone else, even one that had been rw-r--r--.
     */
    private static Path createTemp(Path directory) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Path candidate = directory.resolve(".bewater-" + Long.toUnsignedString(RANDOM.nextLong(), 36) + ".tmp");
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException taken) {
                if (attempt == 100) throw taken;
            }
        }
    }

    /** Gives the replacement the permissions of the file it replaces, where the file system has them. */
    private static void keepPermissions(Path existing, Path replacement) throws IOException {
        if (!Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) return;
        PosixFileAttributeView view = Files.getFileAttributeView(replacement, PosixFileAttributeView.class);
        if (view == null) return;   // Windows: the directory's inherited ACLs apply, as they always did
        view.setPermissions(Files.getPosixFilePermissions(existing));
    }

    // Windows can reject simultaneous replacements of the same destination.
    // Serialize only the rename step; writing the independent temp files can
    // proceed concurrently, and no per-path lock registry needs to be retained.
    // Windows also refuses, as access denied, to replace a file that another
    // program holds open for a moment, as a virus scanner or the indexer does
    // with a file just written. A moment later the rename succeeds, so it is
    // retried briefly before the save fails.
    private static synchronized void replace(Path temp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                move(temp, target);
                return;
            } catch (AccessDeniedException held) {
                if (attempt == REPLACE_ATTEMPTS) throw held;
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw held;
                }
            }
        }
    }

    private static final int REPLACE_ATTEMPTS = 10;

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomicHere) {
            Files.move(temp, target, REPLACE_EXISTING);
        }
    }
}
