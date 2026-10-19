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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Converting several files at once")
class BatchPlanTest {

    private static BatchPlan.Source file(String directory, String name, String format) {
        return new BatchPlan.Source(directory, name, format);
    }

    @Test @DisplayName("each result goes beside its source, named after it")
    void resultsBesideTheirSources() {
        List<BatchPlan.Step> steps = BatchPlan.plan(List.of(
              file("/p/a", "config.json", "JSON"), file("/p/b", "config.toml", "TOML"),
              file("/p/a", "data.csv", "CSV")), "YAML");
        assertThat(steps).extracting(BatchPlan.Step::outputName).containsExactly("config.yaml", "config.yaml", "data.yaml");
        assertThat(steps).allMatch(BatchPlan.Step::converts);
    }

    @Test @DisplayName("a file of no known format, or of the target already, is skipped with the reason")
    void skippedFiles() {
        List<BatchPlan.Step> steps = BatchPlan.plan(List.of(
              file("/p", "notes.txt", null), file("/p", "a.yaml", "YAML"), file("/p", "b.json", "JSON")), "YAML");
        assertThat(steps.get(0).skipped()).isEqualTo("its format is not one the converter reads");
        assertThat(steps.get(1).skipped()).isEqualTo("it is YAML already");
        assertThat(steps.get(2).converts()).isTrue();
    }

    @Test @DisplayName("two files that would write one result are both skipped, each naming the other")
    void sharedResults() {
        List<BatchPlan.Step> steps = BatchPlan.plan(List.of(
              file("/p", "a.yaml", "YAML"), file("/p", "a.yml", "YAML"), file("/q", "a.yaml", "YAML")), "JSON");
        assertThat(steps.get(0).skipped()).isEqualTo("its result, a.json, would also be the result of a.yml");
        assertThat(steps.get(1).skipped()).isEqualTo("its result, a.json, would also be the result of a.yaml");
        // In another directory: no clash.
        assertThat(steps.get(2).converts()).isTrue();
        // Java's class decides its file's name, so every result is Root.java.
        List<BatchPlan.Step> java = BatchPlan.plan(List.of(
              file("/p", "a.json", "JSON"), file("/p", "b.json", "JSON"), file("/p", "c.json", "JSON")), "Java POJO");
        assertThat(java.get(0).skipped()).isEqualTo("its result, Root.java, would also be the result of b.json and c.json");
    }

    @Test @DisplayName("a result that would replace another selected file is skipped; names are compared ignoring case")
    void selectedFilesAreNeverReplaced() {
        List<BatchPlan.Step> steps = BatchPlan.plan(List.of(
              file("/p", "a.json", "JSON"), file("/p", "A.YAML", "YAML")), "YAML");
        assertThat(steps.get(0).skipped()).isEqualTo("its result, a.yaml, would replace a selected file");
        assertThat(steps.get(1).skipped()).isEqualTo("it is YAML already");
        List<BatchPlan.Step> clash = BatchPlan.plan(List.of(
              file("/p", "a.yaml", "YAML"), file("/p", "A.yml", "YAML")), "TOML");
        assertThat(clash).noneMatch(BatchPlan.Step::converts);
    }

    @Test @DisplayName("a schema of a JSON file is name.schema.json, not the file it was made from")
    void schemas() {
        assertThat(BatchPlan.outputNameFor("config.json", "JSON Schema")).isEqualTo("config.schema.json");
        assertThat(BatchPlan.outputNameFor("Makefile", "JSON Schema")).isEqualTo("Makefile.schema.json");
        List<BatchPlan.Step> steps = BatchPlan.plan(List.of(file("/p", "config.json", "JSON")), "JSON Schema");
        assertThat(steps.getFirst().converts()).isTrue();
        assertThat(BatchPlan.outputNameFor("config.yaml", "TypeScript")).isEqualTo("config.ts");
    }
}
