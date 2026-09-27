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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Remembered settings")
class ConverterSettingsTest {

    @Test @DisplayName("every option change reaches the other panels until they stop listening")
    void listenersHearEveryChange() {
        // Each project has its own panel; without this, a panel kept showing an
        // option another project's panel had changed, and wrote it back.
        AtomicInteger heard = new AtomicInteger();
        Runnable listener = heard::incrementAndGet;
        ConverterSettings.addListener(listener);
        try {
            ConverterSettings.saveSortKeys(true);
            ConverterSettings.saveCsvDelimiter(CsvDelimiter.TAB);
            ConverterSettings.saveRowWarningThreshold(5_000);
            // Layout stays each panel's own.
            ConverterSettings.saveWrapLines(true);
            ConverterSettings.saveSplitVertical(true);
            assertThat(heard).hasValue(3);
        } finally {
            ConverterSettings.removeListener(listener);
        }
        ConverterSettings.saveLombok(true);
        assertThat(heard).hasValue(3);
    }

    @Test @DisplayName("outside the IDE the defaults stand, and the row warning keeps its range")
    void defaults() {
        assertThat(ConverterSettings.options().sortKeys()).isFalse();
        assertThat(ConverterSettings.options().detectDates()).isTrue();
        assertThat(ConverterSettings.rowWarningThreshold()).isEqualTo(ConverterSettings.DEFAULT_ROW_WARNING);
    }
}
