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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The toolchains other than the JDK that the generated-source tests compile
 * with: node, cargo, dart, go and python, found on the PATH. A test whose
 * toolchain is missing is skipped rather than failed, so the suite runs on a
 * machine without them; CI has node, cargo, go and python.
 */
final class Toolchains {

    private Toolchains() {}

    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    /** True on a CI runner, which may fetch what a build needs; locally the tests stay offline. */
    static final boolean CI = System.getenv("CI") != null;

    /** The executable of that name on the PATH, or null. */
    static Path find(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        List<String> names = WINDOWS ? List.of(name + ".exe", name + ".bat", name + ".cmd") : List.of(name);
        for (String directory : path.split(File.pathSeparator)) {
            for (String candidateName : names) {
                try {
                    Path candidate = Path.of(directory.isEmpty() ? "." : directory.replace("\"", ""), candidateName);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
                } catch (InvalidPathException notADirectory) {
                    // A PATH entry that is no path cannot hold the tool.
                }
            }
        }
        return null;
    }

    record Result(int exitCode, String output) {}

    /** Runs a command to its end, its output and errors together, failing the test when it outlasts the timeout. */
    static Result run(Path directory, Duration timeout, Map<String, String> environment, List<String> command)
          throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Path log = Files.createTempFile(directory, "process", ".log");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        // As bytes: a tool on Windows writes in the console's code page, not UTF-8.
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertThat(finished).as("Timed out after %s: %s%n%s", timeout, command, output).isTrue();
        return new Result(process.exitValue(), output);
    }
}
