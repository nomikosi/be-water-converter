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

import com.converter.core.CsvConverter;

/**
 * Delimiter choices offered in the options bar, with their converter format.
 * The constant names are what the settings store, so they must not change.
 */
enum CsvDelimiter {
    COMMA("Comma  ,", "comma", CsvConverter.CsvFormat.DEFAULT),
    SEMICOLON("Semicolon  ;", "semicolon", CsvConverter.CsvFormat.SEMICOLON),
    TAB("Tab", "tab", CsvConverter.CsvFormat.TAB);

    private final String label;
    /** How a status message names the delimiter: "semicolon-separated". */
    final String noun;
    final CsvConverter.CsvFormat format;

    CsvDelimiter(String label, String noun, CsvConverter.CsvFormat format) {
        this.label = label;
        this.noun = noun;
        this.format = format;
    }

    /** The option for a delimiter character, or null for one not offered. */
    static CsvDelimiter forChar(char delimiter) {
        for (CsvDelimiter option : values())
            if (option.format.delimiter() == delimiter) return option;
        return null;
    }

    @Override public String toString() { return label; }
}
