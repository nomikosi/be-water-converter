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

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.xml.sax.SAXParseException;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Where in the input a parse failure points, so the editor can put the caret
 * on the offending character instead of making the user read a line number out
 * of the message.
 *
 * <p>Each parser reports this its own way: Jackson (JSON, XML, CSV, TOML) with
 * a 1-based {@link JsonLocation}, SnakeYAML (YAML) with a 0-based {@link Mark},
 * and the DOM parser behind XML Format with a {@link SAXParseException}. The
 * editor used to take these apart itself, which kept the rule out of the plain
 * unit tests and tied the UI to three parsers' exception types.
 *
 * @param line   1-based line
 * @param column 1-based column, or 0 when the parser reported only the line
 */
public record SourcePosition(int line, int column) {

    /**
     * The position {@code failure} or one of its causes reports, or null when
     * none of them carries one.
     */
    public static SourcePosition of(Throwable failure) {
        // Bounded: two exceptions naming each other as cause would loop forever.
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth++ < 32; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException json) {
                JsonLocation location = json.getLocation();
                return location == null ? null : at(location.getLineNr(), location.getColumnNr());
            }
            if (cause instanceof MarkedYAMLException yaml) {
                Mark mark = yaml.getProblemMark();
                return mark == null ? null : at(mark.getLine() + 1, mark.getColumn() + 1);
            }
            if (cause instanceof SAXParseException xml)
                return at(xml.getLineNumber(), xml.getColumnNumber());
        }
        return null;
    }

    private static SourcePosition at(int line, int column) {
        return line < 1 ? null : new SourcePosition(line, Math.max(column, 0));
    }
}
