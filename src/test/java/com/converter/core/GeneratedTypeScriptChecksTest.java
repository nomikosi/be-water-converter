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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The generated interfaces type-check under {@code tsc --strict}, and each
 * sample, written as a literal of its root type, is accepted: every key has its
 * property, no property lacks its key, and every value has the property's type.
 * The compiler is the npm package from a jar, run under node.
 */
@DisplayName("Generated TypeScript type-checks against its own JSON")
class GeneratedTypeScriptChecksTest {

    @TempDir Path temp;

    private static Path node;
    private static Path tsc;

    @BeforeAll static void compiler(@TempDir Path unpacked) throws Exception {
        node = Toolchains.find("node");
        assumeTrue(node != null, "node is not on the PATH");
        String jar = TestProcesses.configured("bewater.typescript.jar");
        // The compiler and the lib.*.d.ts files it reads from beside itself.
        try (JarFile packed = new JarFile(jar)) {
            for (JarEntry entry : java.util.Collections.list(packed.entries())) {
                String name = entry.getName();
                int lib = name.indexOf("/lib/");
                if (entry.isDirectory() || lib < 0 || !name.startsWith("META-INF/resources/webjars/typescript/")) continue;
                Path target = unpacked.resolve(name.substring(lib + 1));
                Files.createDirectories(target.getParent());
                try (InputStream in = packed.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        tsc = unpacked.resolve("lib/tsc.js");
        assertThat(tsc).as("tsc.js in %s", jar).exists();
    }

    @Test @DisplayName("every sample is a value of the type generated from it")
    void samplesAreValuesOfTheirTypes() throws Exception {
        List<String> files = new ArrayList<>();
        List<String> samples = new ArrayList<>(GeneratorSamples.ALL);
        // A root array types its elements.
        samples.add("[{\"a\":1},{\"a\":2,\"b\":null},{\"a\":3,\"c\":[{\"d\":true}]}]");
        samples.add("{\"items\":[{\"a\":1,\"b\":\"x\"},{\"a\":2,\"b\":null}]}");
        for (int i = 0; i < samples.size(); i++) {
            String sample = samples.get(i);
            String type = sample.startsWith("[") ? "Root[]" : "Root";
            Path file = temp.resolve("sample" + i + ".ts");
            Files.writeString(file, new TypeScriptGenerator().fromJson(sample)
                  + "\nexport const sample: " + type + " = " + sample + ";\n");
            files.add(file.toString());
        }
        Toolchains.Result result = check(files);
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test @DisplayName("a value the type does not describe is refused, so the check above checks")
    void aWrongValueIsRefused() throws Exception {
        String generated = new TypeScriptGenerator().fromJson(GeneratorSamples.NAMES_AND_SHAPES);
        List<String> wrong = List.of(
              "{...valid, id: \"1\"}",                       // a string where a number goes
              "{...valid, extra: 1}",                        // a key the type does not have
              "(({ id, ...rest }) => rest)(valid)");         // a required key missing
        for (String value : wrong) {
            Path file = temp.resolve("wrong.ts");
            Files.writeString(file, generated + "\nconst valid: Root = " + GeneratorSamples.NAMES_AND_SHAPES
                  + ";\nexport const sample: Root = " + value + ";\n");
            Toolchains.Result result = check(List.of(file.toString()));
            assertThat(result.exitCode()).as("accepted %s:%n%s", value, result.output()).isNotZero();
        }
    }

    private Toolchains.Result check(List<String> files) throws Exception {
        List<String> command = new ArrayList<>(List.of(node.toString(), tsc.toString(),
              "--strict", "--noEmit", "--target", "es2022", "--module", "es2022"));
        command.addAll(files);
        return Toolchains.run(temp, Duration.ofMinutes(3), Map.of(), command);
    }
}
