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

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory history of successful conversions, most recent first.
 * Oversized conversions are skipped entirely so the history can never pin
 * hundreds of megabytes of editor text in memory.
 */
public final class ConversionHistory {

    public record Entry(String inputFormat, String outputFormat,
                        String input, String output, LocalTime time) {}

    /** Maximum number of entries kept. */
    public static final int MAX_ENTRIES = 20;

    /** Entries whose combined input+output length exceeds this are not recorded. */
    public static final int MAX_ENTRY_CHARS = 1_000_000;

    private final Deque<Entry> entries = new ArrayDeque<>();

    /**
     * Records a conversion. Returns false (and stores nothing) when the entry
     * is over the size limit.
     */
    public synchronized boolean push(Entry entry) {
        if ((long) entry.input().length() + entry.output().length() > MAX_ENTRY_CHARS) {
            return false;
        }
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
        return true;
    }

    /** Snapshot of the entries, most recent first. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
