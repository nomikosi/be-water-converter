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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Isolated compilers and bounded-memory probes, including in the IDE test JVM. */
final class TestProcesses {
    private TestProcesses() {}

    static String configured(String property) {
        String value = System.getProperty(property);
        assertThat(value).as("%s configured by Gradle", property).isNotBlank();
        return value;
    }

    static String javaExecutable() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(configured("bewater.javac")).resolveSibling(name).toString();
    }

    static void run(Path directory, String executable, List<String> arguments) throws Exception {
        // Argument files also avoid Windows' command-line length limit when
        // the SDK and test dependencies make a long classpath.
        Path args = directory.resolve("arguments.txt");
        Files.writeString(args, arguments.stream().map(TestProcesses::quote).collect(Collectors.joining("\n")));
        Path log = directory.resolve("process.log");
        Process process = new ProcessBuilder(executable, "@" + args.toAbsolutePath())
              .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try { finished = process.waitFor(90, TimeUnit.SECONDS); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        assertThat(finished).as("Process timed out: %s", executable).isTrue();
        assertThat(process.exitValue()).as("%s: %s", executable, Files.readString(log)).isZero();
    }

    private static String quote(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
