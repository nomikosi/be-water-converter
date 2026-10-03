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
 * Per-conversion settings, gathered in one value so the pipeline's entry points
 * don't grow another boolean parameter every time an option is added.
 *
 * <p>Instances are immutable; use the {@code with*} methods to derive a variant.
 */
public record ConversionOptions(
      CsvConverter.CsvMode csvMode,
      CsvConverter.CsvFormat csvFormat,
      boolean useLombok,
      boolean detectDates,
      boolean inferTypes,
      boolean sortKeys,
      String filterPath,
      String protoSchema,
      String protoMessage) {

    public static final ConversionOptions DEFAULTS = new ConversionOptions(
          CsvConverter.CsvMode.FLAT_FIRST,
          CsvConverter.CsvFormat.DEFAULT,
          false,   // useLombok
          true,    // detectDates
          true,    // inferTypes
          false,   // sortKeys
          "",      // filterPath: empty means the whole document
          "",      // protoSchema: the .proto text a payload is decoded against, empty for none
          "");     // protoMessage: the message type a payload holds, empty to decode it raw

    /**
     * Normalises the two Protobuf payload settings: null reads as empty, so a
     * record built from a settings store without them still compares equal.
     */
    public ConversionOptions {
        protoSchema = protoSchema == null ? "" : protoSchema;
        protoMessage = protoMessage == null ? "" : protoMessage;
    }

    /** True when a subtree filter is actually set. */
    public boolean hasFilter() {
        return filterPath != null && !filterPath.isBlank();
    }

    public ConversionOptions withCsvMode(CsvConverter.CsvMode mode) {
        return new ConversionOptions(mode, csvFormat, useLombok, detectDates, inferTypes,
              sortKeys, filterPath, protoSchema, protoMessage);
    }

    public ConversionOptions withCsvFormat(CsvConverter.CsvFormat format) {
        return new ConversionOptions(csvMode, format, useLombok, detectDates, inferTypes,
              sortKeys, filterPath, protoSchema, protoMessage);
    }

    public ConversionOptions withLombok(boolean lombok) {
        return new ConversionOptions(csvMode, csvFormat, lombok, detectDates, inferTypes,
              sortKeys, filterPath, protoSchema, protoMessage);
    }

    public ConversionOptions withDetectDates(boolean detect) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detect, inferTypes,
              sortKeys, filterPath, protoSchema, protoMessage);
    }

    public ConversionOptions withInferTypes(boolean infer) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detectDates, infer,
              sortKeys, filterPath, protoSchema, protoMessage);
    }

    public ConversionOptions withSortKeys(boolean sort) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detectDates, inferTypes,
              sort, filterPath, protoSchema, protoMessage);
    }

    public ConversionOptions withFilterPath(String path) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detectDates, inferTypes,
              sortKeys, path == null ? "" : path, protoSchema, protoMessage);
    }

    /**
     * The schema a Protobuf payload is decoded against, and the message type it
     * holds; empty strings decode the payload raw.
     */
    public ConversionOptions withProtoSchema(String schema, String message) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detectDates, inferTypes,
              sortKeys, filterPath, schema, message);
    }

    /** True when a payload is decoded with a schema rather than raw. */
    public boolean hasProtoMessage() {
        return !protoSchema.isBlank() && !protoMessage.isBlank();
    }
}
