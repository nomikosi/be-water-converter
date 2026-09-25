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

    /** File extension a conversion result should carry. */
    public static String extensionFor(String format) {
        Formats.Format known = Formats.named(format);
        return known == null ? "txt" : known.extension();
    }

    /**
     * Name for a conversion result, derived from the source name where there is
     * one so several conversions of different files stay tellable apart.
     */
    public static String nameFor(String sourceName, String format) {
        String ext = extensionFor(format);
        boolean nameless = sourceName == null || sourceName.isBlank();
        Formats.Format root = Formats.named(format);
        if (root != null && root.rootType() != null && (root.fileMustMatchRoot() || nameless)) {
            return root.rootType() + "." + ext;
        }
        if (nameless) return "converted." + ext;
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        return base + "." + ext;
    }
}
