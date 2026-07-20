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

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversionHistory")
class ConversionHistoryTest {

    private static ConversionHistory.Entry entry(String in, String out) {
        return new ConversionHistory.Entry("JSON", "XML", in, out, LocalTime.NOON);
    }

    @Test @DisplayName("entries are returned most recent first")
    void mostRecentFirst() {
        ConversionHistory history = new ConversionHistory();
        history.push(entry("first", "a"));
        history.push(entry("second", "b"));
        assertThat(history.entries()).hasSize(2);
        assertThat(history.entries().get(0).input()).isEqualTo("second");
        assertThat(history.entries().get(1).input()).isEqualTo("first");
    }

    @Test @DisplayName("history is capped at MAX_ENTRIES, dropping the oldest")
    void cappedAtMax() {
        ConversionHistory history = new ConversionHistory();
        for (int i = 0; i < ConversionHistory.MAX_ENTRIES + 5; i++) {
            history.push(entry("input" + i, "out"));
        }
        assertThat(history.entries()).hasSize(ConversionHistory.MAX_ENTRIES);
        assertThat(history.entries().get(0).input())
              .isEqualTo("input" + (ConversionHistory.MAX_ENTRIES + 4));
        assertThat(history.entries().get(ConversionHistory.MAX_ENTRIES - 1).input())
              .isEqualTo("input5");
    }

    @Test @DisplayName("oversized conversions are skipped, not stored")
    void oversizedSkipped() {
        ConversionHistory history = new ConversionHistory();
        String huge = "x".repeat(ConversionHistory.MAX_ENTRY_CHARS + 1);
        assertThat(history.push(entry(huge, ""))).isFalse();
        assertThat(history.isEmpty()).isTrue();
        assertThat(history.push(entry("small", "ok"))).isTrue();
        assertThat(history.entries()).hasSize(1);
    }

    @Test @DisplayName("clear empties the history")
    void clearEmpties() {
        ConversionHistory history = new ConversionHistory();
        history.push(entry("a", "b"));
        history.clear();
        assertThat(history.isEmpty()).isTrue();
        assertThat(history.entries()).isEmpty();
    }
}
