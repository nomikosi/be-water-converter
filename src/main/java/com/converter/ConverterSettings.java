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

package com.converter;

import com.converter.core.ConversionOptions;
import com.converter.core.CsvConverter;

/**
 * The options the tool window remembers across IDE restarts, stored
 * application-wide under the keys earlier versions used.
 *
 * <p>Reading them lived in two places — the panel restoring its controls, and
 * static accessors on the panel that the context menu called — and the
 * 10 to 10,000,000 row range was checked in three. The defaults and the
 * parsing are here once, and nothing outside the UI panel has to reach into it
 * for a setting.
 */
final class ConverterSettings {

    // Stored keys. Renaming one forgets every user's saved choice.
    static final String CSV_MODE       = "beWater.csvMode";
    static final String ROW_THRESHOLD  = "beWater.rowThreshold";
    static final String LOMBOK         = "beWater.lombok";
    static final String INFER_TYPES    = "beWater.csvInferTypes";
    static final String DETECT_DATES   = "beWater.detectDates";
    static final String SPLIT_VERTICAL = "beWater.splitVertical";
    static final String WRAP_LINES     = "beWater.wrapLines";
    static final String SORT_KEYS      = "beWater.sortKeys";
    static final String CSV_DELIMITER  = "beWater.csvDelimiter";

    static final long MIN_ROW_WARNING = 10L;
    static final long MAX_ROW_WARNING = 10_000_000L;
    static final long DEFAULT_ROW_WARNING = 1_000L;

    private ConverterSettings() {}

    /**
     * The conversion options last chosen in the tool window. The subtree filter
     * belongs to the document it was typed for and is never remembered.
     */
    static ConversionOptions options() {
        CsvDelimiter delimiter = enumOr(CsvDelimiter.class, load(CSV_DELIMITER), CsvDelimiter.COMMA);
        CsvConverter.CsvMode mode =
              enumOr(CsvConverter.CsvMode.class, load(CSV_MODE), CsvConverter.CsvMode.FLAT_FIRST);
        return new ConversionOptions(mode, delimiter.format,
              flag(LOMBOK, false), flag(DETECT_DATES, true), flag(INFER_TYPES, true),
              flag(SORT_KEYS, false), "", "", "");
    }

    /** Every option at once, as restoring a history entry sets them. */
    static void saveOptions(ConversionOptions options) {
        store(CSV_MODE, options.csvMode().name());
        CsvDelimiter delimiter = CsvDelimiter.forChar(options.csvFormat().delimiter());
        if (delimiter != null) store(CSV_DELIMITER, delimiter.name());
        store(LOMBOK, String.valueOf(options.useLombok()));
        store(DETECT_DATES, String.valueOf(options.detectDates()));
        store(INFER_TYPES, String.valueOf(options.inferTypes()));
        store(SORT_KEYS, String.valueOf(options.sortKeys()));
        changed();
    }

    // One option each. The settings are application-wide and every open
    // project has its own panel: saving all of them from one panel's controls
    // wrote that panel's stale values over a choice just made in another.
    static void saveCsvMode(CsvConverter.CsvMode mode) { save(CSV_MODE, mode.name()); }

    static void saveCsvDelimiter(CsvDelimiter delimiter) { save(CSV_DELIMITER, delimiter.name()); }

    static void saveLombok(boolean lombok) { save(LOMBOK, String.valueOf(lombok)); }

    static void saveDetectDates(boolean detect) { save(DETECT_DATES, String.valueOf(detect)); }

    static void saveInferTypes(boolean infer) { save(INFER_TYPES, String.valueOf(infer)); }

    static void saveSortKeys(boolean sort) { save(SORT_KEYS, String.valueOf(sort)); }

    /**
     * Runs after any option changes, on the EDT where every change is made:
     * the panels of other open projects follow, instead of showing choices
     * their conversions no longer use. A panel removes its own on dispose.
     */
    private static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void addListener(Runnable listener) { LISTENERS.add(listener); }

    static void removeListener(Runnable listener) { LISTENERS.remove(listener); }

    private static void changed() {
        for (Runnable listener : LISTENERS) listener.run();
    }

    /** The row count above which a CSV conversion asks first. */
    static long rowWarningThreshold() {
        String saved = load(ROW_THRESHOLD);
        if (saved != null) {
            try {
                long value = Long.parseLong(saved);
                if (value >= MIN_ROW_WARNING && value <= MAX_ROW_WARNING) return value;
            } catch (NumberFormatException ignored) { /* keep the default */ }
        }
        return DEFAULT_ROW_WARNING;
    }

    static void saveRowWarningThreshold(long threshold) { save(ROW_THRESHOLD, String.valueOf(threshold)); }

    static boolean wrapLines() { return flag(WRAP_LINES, false); }

    // Layout is each panel's own: a new panel opens with the last choice,
    // and open panels keep theirs, so these two notify nobody.
    static void saveWrapLines(boolean wrap) { store(WRAP_LINES, String.valueOf(wrap)); }

    static boolean splitVertical() { return flag(SPLIT_VERTICAL, false); }

    static void saveSplitVertical(boolean vertical) { store(SPLIT_VERTICAL, String.valueOf(vertical)); }

    /** A stored boolean, or {@code absent} when it was never set. */
    private static boolean flag(String key, boolean absent) {
        String value = load(key);
        return value == null ? absent : "true".equals(value);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknownName) {
            return fallback;
        }
    }

    private static String load(String key) {
        try {
            return com.intellij.ide.util.PropertiesComponent.getInstance().getValue(key);
        } catch (Throwable outsideIde) {
            return null;
        }
    }

    private static void save(String key, String value) {
        store(key, value);
        changed();
    }

    private static void store(String key, String value) {
        try {
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue(key, value);
        } catch (Throwable outsideIde) {
            // Outside a full IDE (tests, standalone) options simply aren't persisted.
        }
    }
}
