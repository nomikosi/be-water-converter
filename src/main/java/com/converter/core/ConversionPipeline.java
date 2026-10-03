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

import static com.converter.core.Formats.FMT_CSV;
import static com.converter.core.Formats.FMT_JAVA;
import static com.converter.core.Formats.FMT_JSON;
import static com.converter.core.Formats.FMT_KOTLIN;
import static com.converter.core.Formats.FMT_PROTO;
import static com.converter.core.Formats.FMT_PROTO_PAYLOAD;
import static com.converter.core.Formats.FMT_SCHEMA;
import static com.converter.core.Formats.FMT_TOML;
import static com.converter.core.Formats.FMT_XML;
import static com.converter.core.Formats.FMT_YAML;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * UI-independent conversion pipeline: normalises any supported input format
 * to JSON (the internal pivot) and renders JSON to any output format. Format
 * detection lives in {@link FormatDetector}; the Format action is routed from
 * here to {@link DocumentFormatter}.
 */
public class ConversionPipeline {

    private final JsonXmlConverter  jsonXml  = new JsonXmlConverter();
    private final JsonYamlConverter jsonYaml = new JsonYamlConverter();
    private final CsvConverter      csv      = new CsvConverter();
    private final TomlConverter     toml     = new TomlConverter();
    private final ProtoConverter    proto    = new ProtoConverter();
    private final ProtoPayloadDecoder payload = new ProtoPayloadDecoder(proto);
    private final JavaPojoGenerator pojo     = new JavaPojoGenerator();
    private final JsonSchemaGenerator schema = new JsonSchemaGenerator();
    private final KotlinDataClassGenerator kotlin = new KotlinDataClassGenerator();
    private final DocumentFormatter formatter = new DocumentFormatter(jsonYaml, toml, csv);

    /**
     * Normalise input to JSON as the internal pivot format.
     * autoClose is applied once for JSON input to repair truncated brackets.
     */
    public String normalizeToJson(String rawInput, String inFmt, ConversionOptions opts)
          throws Exception {
        rawInput = TextDecoder.stripBom(rawInput);
        String input = FMT_JSON.equals(inFmt) ? JsonRepair.autoClose(rawInput) : rawInput;
        boolean inferTypes = opts.inferTypes();
        String pivot = switch (inFmt) {
            // Lenient parse (comments, trailing commas, single quotes), then
            // re-serialize compactly so downstream converters always see strict
            // JSON without paying to indent a string nobody reads.
            case FMT_JSON  -> LenientJson.compact(input);
            case FMT_XML   -> jsonXml.xmlToJson(input, inferTypes);
            case FMT_YAML  -> jsonYaml.yamlToJson(input);
            case FMT_CSV   -> csv.csvToJson(input, inferTypes, opts.csvFormat());
            case FMT_TOML  -> toml.tomlToJson(input);
            case FMT_PROTO -> proto.protoToJson(input);
            case FMT_PROTO_PAYLOAD -> payload.decode(input, opts.protoSchema(), opts.protoMessage());
            default -> throw new UnsupportedOperationException("Unknown input: " + inFmt);
        };
        // One parse and one serialise however many options are on: applying them
        // to strings cost a full round trip each, which measured x2.3 on a 2.7 MB
        // document with both enabled.
        if (opts.hasFilter() || opts.sortKeys()) {
            JsonNode tree = LenientJson.COMPACT.readTree(pivot);
            // Filter first: narrowing means the sort only walks what will render.
            if (opts.hasFilter()) tree = JsonPathFilter.apply(tree, opts.filterPath());
            // Sorting the pivot rather than each renderer's output means every
            // target format inherits key ordering from one place.
            if (opts.sortKeys()) tree = JsonTrees.sorted(tree);
            pivot = LenientJson.COMPACT.writeValueAsString(tree);
        }
        return pivot;
    }

    /**
     * Normalises any supported format to sorted, pretty-printed JSON — the form
     * used to compare two documents that carry the same data in different
     * formats or different key orders.
     *
     * @param opts the user's own settings. Passing defaults here made Compare
     *             read a semicolon- or tab-delimited CSV as a single column named
     *             after the whole header line, so a byte-perfect conversion was
     *             reported as a wholesale difference.
     */
    public String canonicalJson(String input, String fmt, ConversionOptions opts) throws Exception {
        // One parse, one in-memory sort, one serialise — chaining
        // prettyJson(sortKeys(...)) instead cost three full round trips, which
        // measured over a second per Compare click on a 10 MB document.
        JsonNode tree = LenientJson.COMPACT.readTree(normalizeToJson(input, fmt, opts));
        return LenientJson.PRETTY.writeValueAsString(JsonTrees.sorted(tree, true));
    }

    /** JSON pivot -> desired output format. */
    public String renderFromJson(String asJson, String outFmt, ConversionOptions opts)
          throws Exception {
        return switch (outFmt) {
            case FMT_JSON   -> LenientJson.pretty(asJson);
            case FMT_XML    -> jsonXml.jsonToXml(asJson);
            case FMT_YAML   -> jsonYaml.jsonToYaml(asJson);
            case FMT_CSV    -> csv.jsonToCsv(parseJson(asJson), opts.csvMode(), opts.csvFormat());
            case FMT_TOML   -> toml.jsonToToml(asJson);
            case FMT_PROTO  -> proto.jsonToProto(asJson);
            case FMT_JAVA   -> pojo.fromJson(asJson, opts.useLombok(), opts.detectDates());
            case FMT_SCHEMA -> schema.fromJson(asJson);
            case FMT_KOTLIN -> kotlin.fromJson(asJson, opts.detectDates());
            default -> throw new UnsupportedOperationException("Unknown output: " + outFmt);
        };
    }

    /**
     * Pretty-prints or canonicalizes input in its own format (the Format
     * action), keeping the document's line breaks: CRLF when its first line
     * ends in one, as a file from Windows does, else LF. CSV follows its first
     * row separator (also allowing CR), leaving line breaks inside cells alone.
     */
    public String formatInput(String input, String fmt, ConversionOptions opts) throws Exception {
        String formatted = formatter.format(input, fmt, opts);
        if (FMT_CSV.equals(fmt)) {
            char quote = opts.csvFormat().quote();
            String delimiter = String.valueOf(opts.csvFormat().delimiter());
            String separator = LineBreaks.ofCsv(input, delimiter, quote);
            return LineBreaks.convertCsv(formatted, separator == null ? "\n" : separator, delimiter, quote);
        }
        return withLineBreaksOf(input, formatted);
    }

    static String withLineBreaksOf(String original, String text) {
        return LineBreaks.convert(text, "\r\n".equals(LineBreaks.of(original)) ? "\r\n" : "\n");
    }

    /**
     * What Format would silently discard from a document, as a phrase ("2
     * comments and 1 anchor"), or null when nothing would be lost.
     */
    public String formatLosses(String input, String fmt) {
        return formatter.losses(input, fmt);
    }

    /** Parses the JSON pivot once for callers that need the tree (row estimates). */
    public JsonNode parseJson(String json) throws Exception {
        return LenientJson.PRETTY.readTree(json);
    }

    public long estimateCsvRows(JsonNode pivot, CsvConverter.CsvMode mode) {
        return csv.estimateRowCount(pivot, mode);
    }

    public String renderCsv(JsonNode pivot, CsvConverter.CsvMode mode,
          CsvConverter.CsvFormat format) throws Exception {
        return csv.jsonToCsv(pivot, mode, format);
    }
}
