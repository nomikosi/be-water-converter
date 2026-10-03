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
 * unit tests and tied the UI to three parsers' exception types. The failure's
 * message is worded here too, with this position in place of the parser's own
 * location text.
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

    /**
     * A failure's message for the status bar: what went wrong, and where.
     *
     * <p>Jackson ends every message with a location line of its own, "at
     * [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION`
     * disabled); line: 1, column: 9]"; Woodstox adds "at [row,col
     * {unknown-source}]: [1,9]", and CSV "(through reference chain:
     * java.lang.Object[][1])". On a second line that opened a balloon of parser
     * internals for every syntax error. The parser's own words and the position
     * the caret goes to fit on one line. YAML keeps its message, which quotes
     * the offending line.
     *
     * @return the message, or the failure's class name when it carries none
     */
    public static String describe(Throwable failure) {
        if (failure instanceof JsonProcessingException json && json.getOriginalMessage() != null) {
            String words = json.getOriginalMessage().lines()
                  .filter(line -> !line.stripLeading().startsWith("at ["))
                  .map(String::strip)
                  .collect(java.util.stream.Collectors.joining(" "));
            if (!words.isBlank()) {
                SourcePosition position = of(failure);
                return position == null ? words : words + " " + position.where();
            }
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** "(line 3, column 5)", or "(line 3)" when the parser reported no column. */
    String where() {
        return column > 0 ? "(line " + line + ", column " + column + ")" : "(line " + line + ")";
    }
}
