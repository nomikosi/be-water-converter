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

import com.fasterxml.jackson.databind.ObjectMapper;
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
        List<String> samples = List.of(
              KotlinJvmLimitsTest.input(124, "1.5", false),
              KotlinJvmLimitsTest.input(124, "2147483648", false),
              KotlinJvmLimitsTest.input(245, "1", false),
              KotlinJvmLimitsTest.input(245, "1.5", true),
              "{\"first-name\":\"Ada\",\"a b\":1,\"a_b\":2,\"nested\":[{\"x\":1},{\"y\":true}]}",
              "{\"huge\":1e400,\"tiny\":1e-400,\"items\":[9007199254740993,1.5]}", "{}");
        Path output = compile(samples);

        List<URL> urls = new ArrayList<>();
        urls.add(output.toUri().toURL());
        for (String entry : compileClasspath().split(Pattern.quote(File.pathSeparator)))
            urls.add(Path.of(entry).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (int i = 0; i < samples.size(); i++) {
                Class<?> generated = Class.forName("example" + i + ".Root", true, loader);
                assertThat(generated.getDeclaredConstructors()).isNotEmpty();
                if (i < samples.size() - 1)
                    assertThat(generated.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName).contains("copy$default");
            }
        }
    }

    /**
     * Compiling is not the whole job: the classes have to read the JSON they
     * came from and write it back. id and iD have getters Jackson names alike,
     * xAxis a getter Jackson reads as "xaxis", and url and URL two classes
     * whose files are one on Windows and macOS.
     */
    @Test void generatedClassesReadAndWriteTheirOwnJson() throws Exception {
        List<String> samples = List.of(
              "{\"id\":1,\"ID\":2}",
              "{\"xAxis\":1,\"eTag\":\"a\",\"name\":\"n\"}",
              "{\"first-name\":\"Ada\",\"xAxis\":2,\"nested\":{\"iD\":1,\"id\":2}}",
              "{\"url\":{\"host\":\"a\"},\"URL\":{\"port\":1}}",
              "{\"$ref\":\"#/x\",\"when\":\"soon\",\"list\":[{\"aB\":1},{\"aB\":2}]}");
        Path output = compile(samples);

        String bindingCp = TestProcesses.configured("bewater.kotlin.jackson.classpath");
        List<URL> urls = new ArrayList<>();
        urls.add(output.toUri().toURL());
        for (String entry : bindingCp.split(Pattern.quote(File.pathSeparator)))
            urls.add(Path.of(entry).toUri().toURL());
        ObjectMapper plain = new ObjectMapper();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            // Jackson and the Kotlin module live only in this loader, so they are
            // driven reflectively, and module discovery has to search it: it
            // uses the context class loader, which cannot see the module.
            thread.setContextClassLoader(loader);
            Class<?> mapperType = loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperType.getConstructor().newInstance();
            mapperType.getMethod("findAndRegisterModules").invoke(mapper);
            assertThat(String.valueOf(mapperType.getMethod("getRegisteredModuleIds").invoke(mapper)))
                  .contains("KotlinModule");
            for (int i = 0; i < samples.size(); i++) {
                String written;
                try {
                    Class<?> root = Class.forName("example" + i + ".Root", true, loader);
                    Object value = mapperType.getMethod("readValue", String.class, Class.class)
                          .invoke(mapper, samples.get(i), root);
                    written = (String) mapperType.getMethod("writeValueAsString", Object.class)
                          .invoke(mapper, value);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    // Reported as text: the exception's classes exist only in the
                    // isolated loader, and the test runner cannot carry them.
                    Throwable cause = failure.getCause();
                    throw new AssertionError(samples.get(i) + " did not bind: "
                          + cause.getClass().getName() + ": " + cause.getMessage());
                }
                assertThat(plain.readTree(written)).as(samples.get(i)).isEqualTo(plain.readTree(samples.get(i)));
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Generates Kotlin for each sample into package example{i} and compiles it all. */
    private Path compile(List<String> samples) throws Exception {
        String compilerCp = TestProcesses.configured("bewater.kotlin.compiler.classpath");
        Path output = Files.createDirectory(temp.resolve("classes"));
        List<String> command = new ArrayList<>(List.of(
              "-cp", compilerCp,
              "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
              "-classpath", compileClasspath(), "-d", output.toString()));
        for (int i = 0; i < samples.size(); i++) {
            Path source = temp.resolve("Example" + i + ".kt");
            Files.writeString(source, "package example" + i + "\n\n"
                  + new KotlinDataClassGenerator().fromJson(samples.get(i)));
            command.add(source.toString());
        }
        TestProcesses.run(temp, TestProcesses.javaExecutable(), command);
        return output;
    }

    private static String compileClasspath() {
        return TestProcesses.configured("bewater.kotlin.compiler.classpath") + File.pathSeparator
              + TestProcesses.configured("bewater.jackson.annotations.classpath");
    }
}
