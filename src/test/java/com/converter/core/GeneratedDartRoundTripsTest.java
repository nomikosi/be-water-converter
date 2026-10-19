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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The generated classes compile under {@code dart analyze} with no issue,
 * read each sample from what {@code jsonDecode} returns, and write it back
 * through {@code toJson} as the same JSON. Skipped without the Dart SDK.
 */
@DisplayName("Generated Dart reads and writes its own JSON")
class GeneratedDartRoundTripsTest {

    @TempDir Path temp;

    @Test @DisplayName("every sample survives fromJson and toJson, and the code has no analyzer issue")
    void samplesRoundTrip() throws Exception {
        Path dart = Toolchains.find("dart");
        assumeTrue(dart != null, "dart is not on the PATH");
        List<String> samples = GeneratorSamples.ALL;
        StringBuilder imports = new StringBuilder("import 'dart:convert';\nimport 'dart:io';\n\n");
        StringBuilder checks = new StringBuilder();
        for (int i = 0; i < samples.size(); i++) {
            Files.writeString(temp.resolve("sample" + i + ".dart"), new DartGenerator().fromJson(samples.get(i)));
            Files.writeString(temp.resolve("sample" + i + ".json"), samples.get(i));
            imports.append("import 'sample").append(i).append(".dart' as s").append(i).append(";\n");
            checks.append("  check('sample").append(i).append("', (json) => s").append(i)
                  .append(".Root.fromJson(json).toJson());\n");
        }
        Files.writeString(temp.resolve("main.dart"), imports + """

              bool same(Object? a, Object? b) {
                if (a is num && b is num) return a == b;
                if (a is List && b is List) {
                  if (a.length != b.length) return false;
                  for (var i = 0; i < a.length; i++) {
                    if (!same(a[i], b[i])) return false;
                  }
                  return true;
                }
                if (a is Map && b is Map) {
                  if (a.length != b.length) return false;
                  for (final key in a.keys) {
                    if (!b.containsKey(key) || !same(a[key], b[key])) return false;
                  }
                  return true;
                }
                return a == b;
              }

              void check(String name, Map<String, dynamic> Function(Map<String, dynamic>) roundTrip) {
                final original = jsonDecode(File('$name.json').readAsStringSync()) as Map<String, dynamic>;
                try {
                  final written = jsonDecode(jsonEncode(roundTrip(original)));
                  if (!same(original, written)) {
                    print('$name wrote $written for $original');
                    exitCode = 1;
                  }
                } catch (error) {
                  print('$name failed: $error');
                  exitCode = 1;
                }
              }

              void main() {
              """ + checks + "}\n");
        // A dart script needs no package for dart: imports alone.
        Files.writeString(temp.resolve("analysis_options.yaml"), "analyzer:\n  language:\n    strict-casts: true\n");

        Toolchains.Result analysis = Toolchains.run(temp, Duration.ofMinutes(3), Map.of(),
              List.of(dart.toString(), "analyze", "--fatal-infos", "."));
        assertThat(analysis.exitCode()).as(analysis.output()).isZero();
        Toolchains.Result run = Toolchains.run(temp, Duration.ofMinutes(3), Map.of(),
              List.of(dart.toString(), "run", "main.dart"));
        assertThat(run.exitCode()).as(run.output()).isZero();
    }
}
