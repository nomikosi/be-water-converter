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
 * The generated structs pass {@code go vet}, unmarshal each sample with
 * unknown fields refused, so every key has its field, and marshal it back as
 * the same JSON. Skipped without Go.
 */
@DisplayName("Generated Go reads and writes its own JSON")
class GeneratedGoRoundTripsTest {

    @TempDir Path temp;

    private static final String HARNESS = """
          package main

          import (
          	"bytes"
          	"encoding/json"
          	"fmt"
          	"os"
          	"reflect"
          )

          func main() {
          	text, err := os.ReadFile("sample.json")
          	if err != nil {
          		panic(err)
          	}
          	decoder := json.NewDecoder(bytes.NewReader(text))
          	decoder.DisallowUnknownFields()
          	var typed Root
          	if err := decoder.Decode(&typed); err != nil {
          		fmt.Println("did not read:", err)
          		os.Exit(1)
          	}
          	written, err := json.Marshal(typed)
          	if err != nil {
          		fmt.Println("did not write:", err)
          		os.Exit(1)
          	}
          	var original, back any
          	if err := json.Unmarshal(text, &original); err != nil {
          		panic(err)
          	}
          	if err := json.Unmarshal(written, &back); err != nil {
          		panic(err)
          	}
          	if !reflect.DeepEqual(original, back) {
          		fmt.Printf("wrote %s for %s\\n", written, text)
          		os.Exit(1)
          	}
          }
          """;

    @Test @DisplayName("every sample survives json.Unmarshal and json.Marshal")
    void samplesRoundTrip() throws Exception {
        Path go = Toolchains.find("go");
        assumeTrue(go != null, "go is not on the PATH");
        // Never fetch a newer Go than the one installed to build two files.
        Map<String, String> environment = Map.of("GOTOOLCHAIN", "local", "GO111MODULE", "off");
        List<String> samples = GeneratorSamples.FOR_EVERY_LANGUAGE;
        for (int i = 0; i < samples.size(); i++) {
            Path directory = Files.createDirectories(temp.resolve("sample" + i));
            Files.writeString(directory.resolve("root.go"), new GoStructGenerator().fromJson(samples.get(i)));
            Files.writeString(directory.resolve("main.go"), HARNESS);
            Files.writeString(directory.resolve("sample.json"), samples.get(i));
            Toolchains.Result vet = Toolchains.run(directory, Duration.ofMinutes(3), environment,
                  List.of(go.toString(), "vet", "root.go", "main.go"));
            assertThat(vet.exitCode()).as("sample%d: %s", i, vet.output()).isZero();
            Toolchains.Result run = Toolchains.run(directory, Duration.ofMinutes(3), environment,
                  List.of(go.toString(), "run", "root.go", "main.go"));
            assertThat(run.exitCode()).as("sample%d: %s", i, run.output()).isZero();
        }
    }

    @Test @DisplayName("a key no struct tag can name is left to a map, said so in a comment")
    void untaggableKeys() throws Exception {
        String generated = new GoStructGenerator().fromJson(GeneratorSamples.KEYS_GO_CANNOT_TAG);
        assertThat(generated).contains("// The key \"\" cannot be named in a struct tag")
              .contains("// The key \"quote\\\"d\" cannot be named in a struct tag")
              .contains("// The key \"a,b\" cannot be named in a struct tag")
              .doesNotContain("json:\"\"");
        Path go = Toolchains.find("go");
        assumeTrue(go != null, "go is not on the PATH");
        Path directory = Files.createDirectories(temp.resolve("untaggable"));
        Files.writeString(directory.resolve("root.go"), generated);
        Files.writeString(directory.resolve("main.go"), "package main\n\nfunc main() { _ = Root{} }\n");
        Toolchains.Result vet = Toolchains.run(directory, Duration.ofMinutes(3),
              Map.of("GOTOOLCHAIN", "local", "GO111MODULE", "off"), List.of(go.toString(), "vet", "root.go", "main.go"));
        assertThat(vet.exitCode()).as(vet.output()).isZero();
    }
}
