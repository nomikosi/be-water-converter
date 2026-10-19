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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What converting several files at once writes, and what it leaves alone.
 *
 * <p>Each result goes beside its source, named as a scratch file of it would
 * be: {@code config.json} to YAML is {@code config.yaml}. A file is skipped,
 * with the reason, when its format is unknown or already the target, when its
 * result would replace another selected file, and when two files would write
 * the same result: Java's is always {@code Root.java}, and {@code a.yaml} and
 * {@code a.yml} both become {@code a.json}. Paths are compared ignoring case,
 * as Windows and macOS compare them.
 */
public final class BatchPlan {

    private BatchPlan() {}

    /** A selected file: its directory, its name, and its format, null when it has none the converter reads. */
    public record Source(String directory, String name, String format) {
        String path() {
            return key(directory, name);
        }
    }

    /** What happens to one source: the name its result is written as, or why it is skipped. */
    public record Step(Source source, String outputName, String skipped) {
        public boolean converts() {
            return skipped == null;
        }
    }

    public static List<Step> plan(List<Source> sources, String target) {
        Set<String> selected = new HashSet<>();
        for (Source source : sources) selected.add(source.path());
        List<Step> steps = new ArrayList<>();
        Map<String, List<Integer>> byOutput = new HashMap<>();
        for (Source source : sources) {
            if (source.format() == null) {
                steps.add(new Step(source, null, "its format is not one the converter reads"));
                continue;
            }
            if (source.format().equals(target)) {
                steps.add(new Step(source, null, "it is " + target + " already"));
                continue;
            }
            String output = outputNameFor(source.name(), target);
            if (selected.contains(key(source.directory(), output))) {
                steps.add(new Step(source, output, "its result, " + output + ", would replace a selected file"));
                continue;
            }
            byOutput.computeIfAbsent(key(source.directory(), output), k -> new ArrayList<>()).add(steps.size());
            steps.add(new Step(source, output, null));
        }
        for (List<Integer> sharing : byOutput.values()) {
            if (sharing.size() < 2) continue;
            for (int index : sharing) {
                Step step = steps.get(index);
                List<String> others = new ArrayList<>();
                for (int other : sharing) if (other != index) others.add(steps.get(other).source().name());
                steps.set(index, new Step(step.source(), step.outputName(), "its result, " + step.outputName()
                      + ", would also be the result of " + String.join(" and ", others)));
            }
        }
        return steps;
    }

    /**
     * The name a source's result is written as. JSON Schema is the one output
     * whose extension an input shares, so a schema of {@code config.json} is
     * {@code config.schema.json}, not the file it was made from.
     */
    public static String outputNameFor(String sourceName, String target) {
        if (Formats.FMT_SCHEMA.equals(target)) {
            int dot = sourceName.lastIndexOf('.');
            return (dot > 0 ? sourceName.substring(0, dot) : sourceName) + ".schema.json";
        }
        return ConversionFileNames.nameFor(sourceName, target);
    }

    private static String key(String directory, String name) {
        return (directory + "/" + name).toLowerCase(Locale.ROOT);
    }
}
