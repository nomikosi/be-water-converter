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

import java.util.List;
import java.util.Locale;

/**
 * Format metadata shared by the pipeline, menus and file dialogs. How a format
 * looks in an editor is the UI's business and lives there.
 */
public final class Formats {
    private Formats() {}

    public static final String FMT_JSON = "JSON";
    public static final String FMT_XML = "XML";
    public static final String FMT_YAML = "YAML";
    public static final String FMT_CSV = "CSV";
    public static final String FMT_TOML = "TOML";
    public static final String FMT_PROTO = "Protobuf";
    public static final String FMT_JAVA = "Java POJO";
    public static final String FMT_KOTLIN = "Kotlin";
    public static final String FMT_SCHEMA = "JSON Schema";
    /** A binary Protobuf message, pasted as hex or base64: decoded, never written. */
    public static final String FMT_PROTO_PAYLOAD = "Protobuf payload";

    /**
     * @param output            false for a format the plugin only reads
     * @param rootType          the class name a generated source file declares, or null
     * @param fileMustMatchRoot true when a file holding the output must be named after it
     */
    public record Format(String name, List<String> extensions, boolean input, boolean output,
                         String rootType, boolean fileMustMatchRoot) {
        public Format { extensions = List.copyOf(extensions); }
        /** The extension a file of this format is saved with, or "txt" for one with none. */
        public String extension() { return extensions.isEmpty() ? "txt" : extensions.getFirst(); }
    }

    private static final List<Format> ALL = List.of(
          new Format(FMT_JSON, List.of("json"), true, true, null, false),
          new Format(FMT_XML, List.of("xml"), true, true, null, false),
          new Format(FMT_YAML, List.of("yaml", "yml"), true, true, null, false),
          new Format(FMT_CSV, List.of("csv", "tsv"), true, true, null, false),
          new Format(FMT_TOML, List.of("toml"), true, true, null, false),
          new Format(FMT_PROTO, List.of("proto"), true, true, null, false),
          // Pasted as text; a payload has no file extension of its own.
          new Format(FMT_PROTO_PAYLOAD, List.of(), true, false, null, false),
          new Format(FMT_JAVA, List.of("java"), false, true,
                JavaPojoGenerator.ROOT_CLASS_NAME, true),
          new Format(FMT_KOTLIN, List.of("kt"), false, true,
                KotlinDataClassGenerator.ROOT_CLASS_NAME, false),
          new Format(FMT_SCHEMA, List.of("json"), false, true, null, false));

    public static Format named(String name) {
        return ALL.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    public static boolean isInput(String name) {
        Format format = named(name);
        return format != null && format.input();
    }

    public static boolean isOutput(String name) {
        Format format = named(name);
        return format != null && format.output();
    }

    public static String inputForFileName(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return null;
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALL.stream().filter(f -> f.input() && f.extensions().contains(extension))
              .map(Format::name).findFirst().orElse(null);
    }

    public static String[] inputNames() {
        return ALL.stream().filter(Format::input).map(Format::name).toArray(String[]::new);
    }

    public static String[] outputNames() {
        return ALL.stream().filter(Format::output).map(Format::name).toArray(String[]::new);
    }

    public static String[] outputsFor(String input) {
        if (!isInput(input)) return new String[0];
        return ALL.stream().filter(Format::output).map(Format::name)
              .filter(name -> !name.equals(input)).toArray(String[]::new);
    }
}
