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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The generated models compile, and with Pydantic installed each reads its
 * sample and writes it back as the same JSON:
 * {@code model_dump_json(by_alias=True, exclude_unset=True)}. Skipped without
 * Python, and the round trip without Pydantic.
 */
@DisplayName("Generated Python reads and writes its own JSON")
class GeneratedPythonRoundTripsTest {

    @TempDir Path temp;

    private static Path python;

    @BeforeAll static void python() {
        // On Windows "python3" may be the Store's stand-in, which only opens the Store.
        python = Toolchains.WINDOWS ? Toolchains.find("python") : Toolchains.find("python3");
        if (python == null) python = Toolchains.find("python");
        assumeTrue(python != null, "python is not on the PATH");
    }

    /** Every sample with dates typed, and the date sample with them left as text too. */
    private List<String> write() throws Exception {
        List<String> modules = new ArrayList<>();
        List<String> samples = new ArrayList<>(GeneratorSamples.ALL);
        samples.add("{\"items\":[{\"a\":1,\"b\":\"x\"},{\"a\":2,\"b\":null}]}");
        for (int i = 0; i <= samples.size(); i++) {
            boolean dates = i < samples.size();
            String sample = dates ? samples.get(i) : GeneratorSamples.DATES;
            Files.writeString(temp.resolve("sample" + i + ".py"), new PydanticGenerator().fromJson(sample, dates));
            Files.writeString(temp.resolve("sample" + i + ".json"), sample);
            modules.add("sample" + i);
        }
        return modules;
    }

    @Test @DisplayName("every model compiles")
    void modelsCompile() throws Exception {
        List<String> command = new ArrayList<>(List.of(python.toString(), "-m", "py_compile"));
        for (String module : write()) command.add(module + ".py");
        Toolchains.Result result = Toolchains.run(temp, Duration.ofMinutes(2), Map.of(), command);
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test @DisplayName("every sample survives model_validate_json and model_dump_json")
    void samplesRoundTrip() throws Exception {
        Toolchains.Result probe = Toolchains.run(temp, Duration.ofMinutes(1), Map.of(),
              List.of(python.toString(), "-c", "import pydantic"));
        assumeTrue(probe.exitCode() == 0, "pydantic is not installed");
        List<String> modules = write();
        Files.writeString(temp.resolve("check.py"), """
              import importlib
              import json
              import sys
              import warnings

              warnings.simplefilter("error")
              failed = False
              for name in sys.argv[1:]:
                  with open(name + ".json", encoding="utf-8") as source:
                      text = source.read()
                  try:
                      model = importlib.import_module(name).Root.model_validate_json(text)
                      written = json.loads(model.model_dump_json(by_alias=True, exclude_unset=True))
                  except Exception as error:
                      print(name, "failed:", repr(error))
                      failed = True
                      continue
                  if written != json.loads(text):
                      print(name, "wrote", written, "for", json.loads(text))
                      failed = True
              sys.exit(1 if failed else 0)
              """);
        List<String> command = new ArrayList<>(List.of(python.toString(), "check.py"));
        command.addAll(modules);
        Toolchains.Result result = Toolchains.run(temp, Duration.ofMinutes(2), Map.of("PYTHONIOENCODING", "utf-8"),
              command);
        assertThat(result.exitCode()).as(result.output()).isZero();
    }
}
