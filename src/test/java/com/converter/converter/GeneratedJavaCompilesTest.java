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

package com.converter.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Generated Java is handed to javac rather than checked for substrings: a
 * substring can pass while the file does not compile, which is the one thing
 * a user of the generator cannot work around.
 */
@DisplayName("Generated Java compiles")
class GeneratedJavaCompilesTest {

    private final JavaPojoGenerator generator = new JavaPojoGenerator();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
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
    })
    void generatedSourceCompiles(String json) throws Exception {
        assertCompiles(generator.fromJson(json));
    }

    private static void assertCompiles(String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no javac in this JVM");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(
              URI.create("string:///Root.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        Path out = Files.createTempDirectory("bewater-pojo");
        try {
            // The test classpath carries jackson-annotations, which @JsonProperty needs.
            List<String> options = List.of("-d", out.toString(),
                  "-classpath", System.getProperty("java.class.path"), "-proc:none", "-Xlint:none");
            boolean ok = compiler.getTask(null, null, diagnostics, options, null, List.of(unit)).call();
            assertThat(ok)
                  .describedAs("javac said:\n%s\n\nfor:\n%s", diagnostics.getDiagnostics(), source)
                  .isTrue();
        } finally {
            try (var files = Files.walk(out)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
