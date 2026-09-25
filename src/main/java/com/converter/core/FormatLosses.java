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

/** Counts of what a Format would drop. */
public record FormatLosses(int comments, int anchors) {
    /** "2 comments and 1 anchor", or null when there is nothing to report. */
    public String describe() {
        String c = comments == 0 ? null : comments + (comments == 1 ? " comment" : " comments");
        String a = anchors == 0 ? null : anchors + (anchors == 1 ? " anchor" : " anchors");
        if (c == null) return a;
        return a == null ? c : c + " and " + a;
    }
}
