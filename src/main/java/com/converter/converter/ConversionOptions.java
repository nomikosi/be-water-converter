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

package com.converter.converter;

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
      boolean sortKeys) {

    public static final ConversionOptions DEFAULTS = new ConversionOptions(
          CsvConverter.CsvMode.FLAT_FIRST,
          CsvConverter.CsvFormat.DEFAULT,
          false,   // useLombok
          true,    // detectDates
          true,    // inferTypes
          false);  // sortKeys

    public ConversionOptions withCsvMode(CsvConverter.CsvMode mode) {
        return new ConversionOptions(mode, csvFormat, useLombok, detectDates, inferTypes, sortKeys);
    }

    public ConversionOptions withCsvFormat(CsvConverter.CsvFormat format) {
        return new ConversionOptions(csvMode, format, useLombok, detectDates, inferTypes, sortKeys);
    }

    public ConversionOptions withLombok(boolean lombok) {
        return new ConversionOptions(csvMode, csvFormat, lombok, detectDates, inferTypes, sortKeys);
    }

    public ConversionOptions withDetectDates(boolean detect) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detect, inferTypes, sortKeys);
    }

    public ConversionOptions withInferTypes(boolean infer) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detectDates, infer, sortKeys);
    }

    public ConversionOptions withSortKeys(boolean sort) {
        return new ConversionOptions(csvMode, csvFormat, useLombok, detectDates, inferTypes, sort);
    }
}
