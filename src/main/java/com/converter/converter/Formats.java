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

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.util.List;
import java.util.Locale;

/** Format metadata shared by the pipeline, menus, file dialogs and editors. */
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

    public record Format(String name, List<String> extensions, boolean input,
                         String syntax, String rootType, boolean fileMustMatchRoot) {
        public Format { extensions = List.copyOf(extensions); }
        public String extension() { return extensions.getFirst(); }
    }

    private static final List<Format> ALL = List.of(
          new Format(FMT_JSON, List.of("json"), true, SyntaxConstants.SYNTAX_STYLE_JSON, null, false),
          new Format(FMT_XML, List.of("xml"), true, SyntaxConstants.SYNTAX_STYLE_XML, null, false),
          new Format(FMT_YAML, List.of("yaml", "yml"), true, SyntaxConstants.SYNTAX_STYLE_YAML, null, false),
          new Format(FMT_CSV, List.of("csv"), true, SyntaxConstants.SYNTAX_STYLE_CSV, null, false),
          new Format(FMT_TOML, List.of("toml"), true, SyntaxConstants.SYNTAX_STYLE_INI, null, false),
          new Format(FMT_PROTO, List.of("proto"), true, SyntaxConstants.SYNTAX_STYLE_PROTO, null, false),
          new Format(FMT_JAVA, List.of("java"), false, SyntaxConstants.SYNTAX_STYLE_JAVA,
                JavaPojoGenerator.ROOT_CLASS_NAME, true),
          new Format(FMT_KOTLIN, List.of("kt"), false, SyntaxConstants.SYNTAX_STYLE_KOTLIN,
                KotlinDataClassGenerator.ROOT_CLASS_NAME, false),
          new Format(FMT_SCHEMA, List.of("json"), false, SyntaxConstants.SYNTAX_STYLE_JSON, null, false));

    public static List<Format> all() { return ALL; }

    public static Format named(String name) {
        return ALL.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    public static boolean isInput(String name) {
        Format format = named(name);
        return format != null && format.input();
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
        return ALL.stream().map(Format::name).toArray(String[]::new);
    }

    public static String[] outputsFor(String input) {
        if (!isInput(input)) return new String[0];
        return ALL.stream().map(Format::name).filter(name -> !name.equals(input)).toArray(String[]::new);
    }
}
