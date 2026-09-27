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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedJavaCompilesTest {
    @TempDir Path temp;
    private static final String[] SAMPLES = {
          "{\"huge\":1e400,\"tiny\":1e-400,\"precise\":[9007199254740993,1.5]}",
          "{\"id\":1,\"name\":\"Ada\",\"tags\":[\"a\"],\"address\":{\"street\":\"x\",\"zip\":\"01234\"}}",
          "{\"class\":1,\"int\":2,\"for\":3,\"true\":4,\"_\":5,\"var\":6,\"record\":7}",
          "{\"first-name\":\"a\",\"1st\":2,\"a b\":3,\"a_b\":4,\"aB\":5,\"$ref\":6}",
          "{\"größe\":1,\"日本\":2,\"a\\\"b\\\\c\\nd\":3}",
          "{\"when\":\"2024-01-01\",\"at\":\"2024-01-01T10:00:00\",\"ts\":\"2024-01-01T10:00:00Z\"}",
          "{\"e\":{},\"l\":[],\"n\":null,\"nested\":[[{\"x\":1}]]}",
          "{\"xs\":[1,\"two\"],\"os\":[{\"a\":1},{\"b\":\"x\"}],\"ns\":[1,2.5,null]}",
          "{\"List\":{\"a\":1},\"String\":{\"b\":2},\"Object\":3,\"Root\":{\"c\":4},\"root\":{\"d\":5}}",
          "[{\"a\":1},{\"b\":{\"c\":[1,2]}}]",
          "{\"big\":12345678901234567890,\"dec\":1.5,\"neg\":-1,\"long\":12345678901,\"user\":{},\"User\":{\"x\":1}}",
          "{}",
          "{\"data\":{\"id\":1},\"all-args-constructor\":{},\"no-args-constructor\":{\"x\":true}}"
    };

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void generatedClassesCompileAndLoad(boolean lombok) throws Exception {
        String annotations = TestProcesses.configured("bewater.jackson.annotations.classpath");
        String lombokCp = TestProcesses.configured("bewater.lombok.classpath");
        String classpath = annotations + File.pathSeparator + lombokCp;
        Path output = Files.createDirectory(temp.resolve("classes"));
        List<String> command = new ArrayList<>(List.of("-encoding", "UTF-8", "-classpath", classpath,
              "-d", output.toString()));
        if (lombok) command.addAll(List.of("-processorpath", lombokCp, "-proc:full"));
        else command.add("-proc:none");
        List<String> samples = new ArrayList<>(List.of(SAMPLES));
        samples.add(KotlinJvmLimitsTest.input(254, "1", false));
        samples.add(KotlinJvmLimitsTest.input(255, "1", false));
        for (int i = 0; i < samples.size(); i++) {
            Path source = Files.createDirectory(temp.resolve("example" + i)).resolve("Root.java");
            Files.writeString(source, "package example" + i + ";\n\n"
                  + new JavaPojoGenerator().fromJson(samples.get(i), lombok));
            command.add(source.toString());
        }
        TestProcesses.run(temp, TestProcesses.configured("bewater.javac"), command);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()},
              ClassLoader.getPlatformClassLoader())) {
            for (int i = 0; i < samples.size(); i++) {
                Class<?> type = Class.forName("example" + i + ".Root", true, loader);
                assertThat(type.getDeclaredConstructors()).isNotEmpty();
                if (lombok && type.getDeclaredFields().length > 0)
                    assertThat(type.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                          .contains("equals", "hashCode", "toString");
                if (lombok && i >= samples.size() - 2) {
                    int expected = i == samples.size() - 2 ? 254 : 0;
                    assertThat(Arrays.stream(type.getDeclaredConstructors())
                          .mapToInt(java.lang.reflect.Constructor::getParameterCount).max().orElseThrow())
                          .isEqualTo(expected);
                }
            }
        }
    }

    /**
     * Compiling is not the whole job: with Lombok the classes have to read the
     * JSON they came from and write it back. xAxis gets the getter getXAxis,
     * which Jackson reads as "xaxis"; an object whose only key was $ref lost its
     * field to Lombok; url and URL are two classes whose files are one on
     * Windows and macOS.
     *
     * <p>Every compile sample is bound too, not only these: binding just the
     * naming cases let a class generated for {@code {}} ship that Jackson could
     * not write back at all. Dates need the JavaTimeModule, as they do for the
     * user, and a sample with an array root is bound element by element.
     */
    @org.junit.jupiter.api.Test
    void lombokClassesReadAndWriteTheirOwnJson() throws Exception {
        List<String> samples = new ArrayList<>(List.of(
              "{\"xAxis\":1,\"eTag\":\"a\",\"name\":\"n\"}",
              "{\"schema\":{\"$ref\":\"#/components/schemas/Pet\"}}",
              "{\"url\":{\"host\":\"a\"},\"URL\":{\"port\":1}}",
              "{\"id\":1,\"ID\":2,\"iPhone\":{\"aB\":[1,2]}}",
              "{\"first-name\":\"Ada\",\"X-Axis\":2}"));
        samples.addAll(List.of(SAMPLES));
        samples.add(KotlinJvmLimitsTest.input(255, "1", false));
        String lombokCp = TestProcesses.configured("bewater.lombok.classpath");
        String classpath = TestProcesses.configured("bewater.jackson.annotations.classpath")
              + File.pathSeparator + lombokCp;
        Path output = Files.createDirectory(temp.resolve("bound"));
        List<String> command = new ArrayList<>(List.of("-encoding", "UTF-8", "-classpath", classpath,
              "-d", output.toString(), "-processorpath", lombokCp, "-proc:full"));
        for (int i = 0; i < samples.size(); i++) {
            Path source = Files.createDirectories(temp.resolve("bound-src").resolve("bound" + i)).resolve("Root.java");
            Files.writeString(source, "package bound" + i + ";\n\n" + new JavaPojoGenerator().fromJson(samples.get(i), true));
            command.add(source.toString());
        }
        TestProcesses.run(temp, TestProcesses.configured("bewater.javac"), command);
        // The test's own loader as parent, so the generated annotations are the
        // ones this ObjectMapper reads.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
              .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
              .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
              .disable(com.fasterxml.jackson.databind.DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        var exact = new com.fasterxml.jackson.databind.ObjectMapper()
              .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
              .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()},
              GeneratedJavaCompilesTest.class.getClassLoader())) {
            for (int i = 0; i < samples.size(); i++) {
                Class<?> root = Class.forName("bound" + i + ".Root", true, loader);
                com.fasterxml.jackson.databind.JsonNode tree = exact.readTree(samples.get(i));
                while (tree.isArray() && tree.size() > 0 && tree.get(0).isArray()) tree = tree.get(0);
                for (com.fasterxml.jackson.databind.JsonNode document : tree.isArray() ? tree : List.of(tree)) {
                    Object value = mapper.treeToValue(document, root);
                    com.fasterxml.jackson.databind.JsonNode back = exact.readTree(mapper.writeValueAsString(value));
                    assertThat(withoutNulls(back).equals(NUMBERS_BY_VALUE, withoutNulls(document)))
                          .as("%s read and written back as %s", document, back).isTrue();
                }
            }
        }
    }

    /**
     * A tree without its null-valued fields: a class writes every field it has,
     * so a key some elements lacked comes back as null.
     */
    private static com.fasterxml.jackson.databind.JsonNode withoutNulls(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            node.properties().forEach(e -> { if (!e.getValue().isNull()) out.set(e.getKey(), withoutNulls(e.getValue())); });
            return out;
        }
        if (node.isArray()) {
            var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(withoutNulls(item)));
            return out;
        }
        return node;
    }

    /** Numbers equal by value, however they are spelled: 1e400 and 1E+400, 1.0 and 1.00. */
    private static final java.util.Comparator<com.fasterxml.jackson.databind.JsonNode> NUMBERS_BY_VALUE = (a, b) ->
          a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1;
}
