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

import java.util.Map;

/**
 * File naming for conversion results.
 *
 * <p>Deliberately free of {@code com.intellij} imports: the naming rules are
 * ordinary logic and belong in the fast, platform-free test loop. Keeping them
 * next to the scratch-file code pulled the IDE classpath into {@code unitTest},
 * where it is not available.
 */
public final class ConversionFileNames {

    private ConversionFileNames() {}

    private static final Map<String, String> EXTENSIONS = Map.of(
          ConversionPipeline.FMT_SCHEMA, "json",
          ConversionPipeline.FMT_JSON,   "json",
          ConversionPipeline.FMT_XML,    "xml",
          ConversionPipeline.FMT_YAML,   "yaml",
          ConversionPipeline.FMT_CSV,    "csv",
          ConversionPipeline.FMT_TOML,   "toml",
          ConversionPipeline.FMT_PROTO,  "proto",
          ConversionPipeline.FMT_JAVA,   "java",
          ConversionPipeline.FMT_KOTLIN, "kt");

    /** File extension a conversion result should carry. */
    public static String extensionFor(String format) {
        return EXTENSIONS.getOrDefault(format, "txt");
    }

    /**
     * How a code generator's root type constrains the result's file name.
     *
     * @param rootType     the type the generator always emits at the root.
     * @param mustMatch    true when the language requires the file to be named
     *                     after that type, so the source name cannot be kept.
     */
    private record RootType(String rootType, boolean mustMatch) {}

    /**
     * The code-generating formats. One row per generator, so adding a language
     * does not mean adding another branch below.
     *
     * <p>Java must match: IntelliJ reports {@code CLASS_WRONG_FILE_NAME} on a
     * public class whose file disagrees, and the generator always emits
     * {@code public class Root}. Kotlin allows several top-level declarations
     * per file, so its name is free and only stands in for a missing source
     * name.
     */
    private static final Map<String, RootType> ROOT_TYPES = Map.of(
          ConversionPipeline.FMT_JAVA,
          new RootType(JavaPojoGenerator.ROOT_CLASS_NAME, true),
          ConversionPipeline.FMT_KOTLIN,
          new RootType(KotlinDataClassGenerator.ROOT_CLASS_NAME, false));

    /**
     * Name for a conversion result, derived from the source name where there is
     * one so several conversions of different files stay tellable apart.
     */
    public static String nameFor(String sourceName, String format) {
        String ext = extensionFor(format);
        boolean nameless = sourceName == null || sourceName.isBlank();
        RootType root = ROOT_TYPES.get(format);
        if (root != null && (root.mustMatch() || nameless)) {
            return root.rootType() + "." + ext;
        }
        if (nameless) return "converted." + ext;
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        return base + "." + ext;
    }
}
