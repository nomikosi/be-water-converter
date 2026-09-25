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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

class GeneratedKotlinLoadsTest {
    @TempDir Path temp;

    @Test void generatedClassesCompileAndLoadIncludingJvmSlotBoundaries() throws Exception {
        String compilerCp = System.getProperty("bewater.kotlin.compiler.classpath");
        assertThat(compilerCp).as("isolated Kotlin compiler configured by Gradle").isNotBlank();
        String annotations = System.getProperty("bewater.jackson.annotations.classpath");
        assertThat(annotations).as("Jackson annotations configured by Gradle").isNotBlank();
        String classpath = compilerCp + File.pathSeparator + annotations;
        Path output = Files.createDirectory(temp.resolve("classes"));
        List<String> command = new ArrayList<>(List.of(
              "-cp", compilerCp,
              "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
              "-classpath", classpath, "-d", output.toString()));
        List<String> samples = List.of(
              KotlinJvmLimitsTest.input(124, "1.5", false),
              KotlinJvmLimitsTest.input(124, "2147483648", false),
              KotlinJvmLimitsTest.input(245, "1", false),
              KotlinJvmLimitsTest.input(245, "1.5", true),
              "{\"first-name\":\"Ada\",\"a b\":1,\"a_b\":2,\"nested\":[{\"x\":1},{\"y\":true}]}",
              "{\"huge\":1e400,\"tiny\":1e-400,\"items\":[9007199254740993,1.5]}", "{}");
        for (int i = 0; i < samples.size(); i++) {
            Path source = temp.resolve("Example" + i + ".kt");
            Files.writeString(source, "package example" + i + "\n\n"
                  + new KotlinDataClassGenerator().fromJson(samples.get(i)));
            command.add(source.toString());
        }
        TestProcesses.run(temp, TestProcesses.javaExecutable(), command);

        List<URL> urls = new ArrayList<>();
        urls.add(output.toUri().toURL());
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) urls.add(Path.of(entry).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (int i = 0; i < samples.size(); i++) {
                Class<?> generated = Class.forName("example" + i + ".Root", true, loader);
                assertThat(generated.getDeclaredConstructors()).isNotEmpty();
                if (i < samples.size() - 1)
                    assertThat(generated.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName).contains("copy$default");
            }
        }
    }
}
