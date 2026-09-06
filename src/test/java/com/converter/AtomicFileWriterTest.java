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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class AtomicFileWriterTest {
    @TempDir Path dir;

    @Test void preservesAnExistingSiblingAndWritesUtf8() throws Exception {
        Path target = dir.resolve("output.json");
        Path sibling = dir.resolve("output.json.bewater.tmp");
        Files.writeString(target, "old");
        Files.writeString(sibling, "unrelated contents");
        AtomicFileWriter.write(target, "{\"name\":\"Νερό 🌊\"}");
        assertThat(Files.readString(target)).isEqualTo("{\"name\":\"Νερό 🌊\"}");
        assertThat(Files.readString(sibling)).isEqualTo("unrelated contents");
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).containsExactlyInAnyOrder(target, sibling);
        }
    }

    @Test void overlappingSavesWriteWholeFilesAndCleanUpTheirOwnTemps() throws Exception {
        Path target = dir.resolve("output.json");
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < 8; i++) payloads.add(("payload-" + i + "\n").repeat(10_000));
        CyclicBarrier start = new CyclicBarrier(payloads.size());
        try (var pool = Executors.newFixedThreadPool(payloads.size())) {
            List<Future<?>> writes = new ArrayList<>();
            for (String payload : payloads) {
                writes.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    AtomicFileWriter.write(target, payload);
                    return null;
                }));
            }
            for (Future<?> write : writes) write.get(10, TimeUnit.SECONDS);
        }
        assertThat(Files.readString(target)).isIn(payloads);
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).containsExactly(target);
        }
    }

    @Test void failedReplacementKeepsDestinationAndRemovesItsTemp() throws Exception {
        Path target = Files.createDirectory(dir.resolve("destination"));
        Path existing = target.resolve("keep.json");
        Files.writeString(existing, "keep me");
        assertThatThrownBy(() -> AtomicFileWriter.write(target, "new"))
              .isInstanceOf(IOException.class);
        assertThat(Files.readString(existing)).isEqualTo("keep me");
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).containsExactly(target);
        }
    }
}
