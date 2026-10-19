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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The generated structs compile with serde and serde_json, read each sample,
 * and write it back as the same JSON, numbers compared by value. Locally cargo
 * builds offline, from crates already in its cache, and the test is skipped
 * when they are not there; on CI it fetches them.
 */
@DisplayName("Generated Rust reads and writes its own JSON")
class GeneratedRustRoundTripsTest {

    @TempDir Path temp;

    @Test @DisplayName("every sample survives serde_json::from_str and to_value")
    void samplesRoundTrip() throws Exception {
        Path cargo = Toolchains.find("cargo");
        assumeTrue(cargo != null, "cargo is not on the PATH");
        Files.writeString(temp.resolve("Cargo.toml"), """
              [package]
              name = "bewater-generated-rust"
              version = "0.0.0"
              edition = "2021"

              [dependencies]
              serde = { version = "1", features = ["derive"] }
              serde_json = "1"
              """);
        Path src = Files.createDirectories(temp.resolve("src"));
        StringBuilder main = new StringBuilder("""
              #![allow(dead_code)]
              use serde_json::Value;

              fn same(a: &Value, b: &Value) -> bool {
                  match (a, b) {
                      (Value::Number(x), Value::Number(y)) => x.as_f64() == y.as_f64(),
                      (Value::Array(x), Value::Array(y)) => {
                          x.len() == y.len() && x.iter().zip(y).all(|(p, q)| same(p, q))
                      }
                      (Value::Object(x), Value::Object(y)) => {
                          x.len() == y.len() && x.iter().all(|(k, v)| y.get(k).map_or(false, |w| same(v, w)))
                      }
                      _ => a == b,
                  }
              }

              fn check<T: serde::de::DeserializeOwned + serde::Serialize>(name: &str, text: &str) -> bool {
                  let typed: T = match serde_json::from_str(text) {
                      Ok(typed) => typed,
                      Err(error) => {
                          println!("{name} did not read: {error}");
                          return false;
                      }
                  };
                  let written = serde_json::to_value(&typed).unwrap();
                  let original: Value = serde_json::from_str(text).unwrap();
                  if !same(&written, &original) {
                      println!("{name} wrote {written} for {original}");
                      return false;
                  }
                  true
              }

              """);
        List<String> samples = GeneratorSamples.ALL;
        StringBuilder checks = new StringBuilder();
        for (int i = 0; i < samples.size(); i++) {
            Files.writeString(src.resolve("sample" + i + ".rs"), new RustSerdeGenerator().fromJson(samples.get(i)));
            Files.writeString(src.resolve("sample" + i + ".json"), samples.get(i));
            main.append("mod sample").append(i).append(" {\n    include!(\"sample").append(i).append(".rs\");\n}\n");
            checks.append("    ok &= check::<sample").append(i).append("::Root>(\"sample").append(i)
                  .append("\", include_str!(\"sample").append(i).append(".json\"));\n");
        }
        main.append("\nfn main() {\n    let mut ok = true;\n").append(checks)
              .append("    if !ok {\n        std::process::exit(1);\n    }\n}\n");
        Files.writeString(src.resolve("main.rs"), main.toString());

        List<String> command = new ArrayList<>(List.of(cargo.toString(), "run", "--quiet"));
        if (!Toolchains.CI) command.add("--offline");
        // One target directory for every run, so serde is compiled once, not per test run.
        Path target = Path.of(System.getProperty("java.io.tmpdir"), "bewater-generated-rust-target");
        Toolchains.Result result = Toolchains.run(temp, Duration.ofMinutes(10),
              Map.of("CARGO_TARGET_DIR", target.toString()), command);
        assumeTrue(Toolchains.CI || !result.output().contains("offline"),
              "serde is not in cargo's cache, and this machine is not CI:\n" + result.output());
        assertThat(result.exitCode()).as(result.output()).isZero();
    }
}
