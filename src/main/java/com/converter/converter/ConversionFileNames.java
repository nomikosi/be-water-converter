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
     * Name for a conversion result, derived from the source name where there is
     * one so several conversions of different files stay tellable apart.
     *
     * <p>Java is the exception: {@link JavaPojoGenerator} always names the root
     * class {@code Root}, and IntelliJ reports {@code CLASS_WRONG_FILE_NAME} on a
     * public class whose file disagrees — so a Java result is always Root.java.
     */
    public static String nameFor(String sourceName, String format) {
        String ext = extensionFor(format);
        if (ConversionPipeline.FMT_JAVA.equals(format)) {
            return JavaPojoGenerator.ROOT_CLASS_NAME + "." + ext;
        }
        // Kotlin allows several top-level classes per file, so the file name is
        // free — but matching the root class keeps results tellable apart.
        if (ConversionPipeline.FMT_KOTLIN.equals(format) && (sourceName == null || sourceName.isBlank())) {
            return KotlinDataClassGenerator.ROOT_CLASS_NAME + "." + ext;
        }
        if (sourceName == null || sourceName.isBlank()) return "converted." + ext;
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        return base + "." + ext;
    }
}
