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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public class JsonYamlConverter {

    /**
     * SnakeYAML's default code-point limit is ~3 MB, which rejected YAML files
     * well under the plugin's own 10 MB open warning. Raised to match, leaving
     * the alias and nesting limits at their defaults so billion-laughs input is
     * still refused.
     */
    static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;

    public JsonYamlConverter() {
        // No INDENT_OUTPUT: writes the internal pivot only, which is re-parsed.
        jsonMapper = PivotJson.mapper()
              // SnakeYAML resolves YAML timestamps to java.util.Date; without
              // this they would serialise as epoch millis rather than the text
              // the document actually contained.
              .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        yamlMapper = YAMLMapper.builder(
              YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)  // suppress "---"
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)           // bare strings, no 'quoting'
                    // On its own MINIMIZE_QUOTES emitted the STRING "123" as
                    // bare 123, which YAML reads back as a number: the round
                    // trip retyped the value. This quotes the numeric-looking
                    // ones and leaves ordinary text bare, which is what keeps
                    // the output readable — the point of MINIMIZE_QUOTES.
                    //
                    // It does NOT cover every such string: "0x1F" is emitted bare
                    // and read back as 31, because hex is a genuine integer form
                    // under CoreScalarResolver. (Times and leading-zero runs used
                    // to belong on this list; the resolver no longer rewrites
                    // them.) Closing the hex case needs quoting everything, which
                    // costs the readable output this project deliberately tests
                    // for, so it is a documented limit.
                    .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                    .build()
        ).build();
    }

    public String jsonToYaml(String json) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input JSON must not be empty");
        JsonNode node = jsonMapper.readTree(json);
        return yamlMapper.writeValueAsString(node);
    }

    /**
     * Converts YAML to JSON. Multi-document input ("---"-separated, e.g.
     * Kubernetes manifests) becomes a JSON array with one element per document;
     * a single document maps to its JSON value directly.
     *
     * <p>Parsed through SnakeYAML's composer rather than Jackson's YAML parser:
     * Jackson works at the event level and never resolves anchors, so {@code *ref}
     * arrived as the literal string {@code "ref"} and a {@code <<:} merge key
     * survived as a key of that name with the merged content discarded.
     */
    public String yamlToJson(String yaml) throws Exception {
        if (yaml == null || yaml.isBlank())
            throw new IllegalArgumentException("Input YAML must not be empty");

        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        // A repeated key silently kept only the last value. YAML says duplicate
        // keys are an error; SnakeYAML merely defaults to allowing them.
        options.setAllowDuplicateKeys(false);
        // SafeConstructor refuses arbitrary Java type tags, so a hostile
        // document cannot cause class instantiation.
        Yaml composer = new Yaml(new SafeConstructor(options),
              UNUSED_REPRESENTER, UNUSED_DUMPER_OPTIONS, options, new CoreScalarResolver());

        java.util.List<JsonNode> docs = new java.util.ArrayList<>();
        for (Object document : composer.loadAll(ConversionPipeline.stripBom(yaml))) {
            JsonNode node = document == null ? null : jsonMapper.valueToTree(document);
            if (node == null || node.isMissingNode()) node = jsonMapper.nullNode();
            rejectCollidingKeys(document, node);
            docs.add(node);
        }
        // A trailing "---" with nothing after it terminates the last document
        // rather than starting an empty one, and yamlTrailingSeparator pins
        // that. Exactly one such document is dropped, and only when the source
        // really does end that way: a document the user WROTE as null is theirs,
        // and popping it changed the document count — the same index-shifting
        // loss the interior case was fixed for.
        if (docs.size() > 1 && docs.get(docs.size() - 1).isNull() && endsWithBareSeparator(yaml))
            docs.remove(docs.size() - 1);
        // A stream that is nothing but empty documents carries no content at all,
        // which is a different thing from one that happens to contain a null.
        if (docs.isEmpty() || docs.stream().allMatch(JsonNode::isNull))
            throw new IllegalArgumentException("Input YAML contains no documents");

        JsonNode node = docs.size() == 1
              ? docs.get(0)
              : jsonMapper.createArrayNode().addAll(docs);
        return jsonMapper.writeValueAsString(node);
    }

    /**
     * Scalar resolution without the YAML 1.1 rules that rewrite data.
     *
     * <p>SnakeYAML defaults to YAML 1.1, where {@code 12:30:00} is sexagesimal
     * for 45000, {@code 0777} is octal for 511, and a bare date becomes a
     * {@code java.util.Date} that then serialised as a timestamp string. Those
     * three silently changed values that every modern YAML producer means as
     * text, and JSON has no date type to receive the third.
     *
     * <p>This drops exactly those: the sexagesimal alternatives, bare octal, and
     * the timestamp resolver. Booleans, null, plain integers, floats, merge keys
     * and {@code 0x}/{@code 0b} forms all still resolve, so {@code yes} is still
     * a boolean and nothing else about reading YAML changes.
     */
    private static final class CoreScalarResolver extends org.yaml.snakeyaml.resolver.Resolver {
        @Override protected void addImplicitResolvers() {
            addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.BOOL, java.util.regex.Pattern.compile(
                  "^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE"
                  + "|on|On|ON|off|Off|OFF)$"), "yYnNtTfFoO");
            // Neither the sexagesimal "[-+]?[1-9][0-9_]*(:[0-5]?[0-9])+" nor bare
            // octal "0[0-7_]+", so 12:30:00 and 0777 stay the text they were
            // written as. YAML 1.2's explicit 0o777 is deliberately absent too:
            // SafeConstructor reads a leading 0 as octal and then calls
            // parseInt("o777", 8), so tagging it INT throws rather than converts.
            // Unresolved, it is simply the string it looks like.
            addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.INT, java.util.regex.Pattern.compile(
                  "^(?:[-+]?0b[0-1_]+|[-+]?(?:0|[1-9][0-9_]*)"
                  + "|[-+]?0x[0-9a-fA-F_]+)$"), "-+0123456789");
            // A dot or an exponent is required, so 0777 cannot land here either
            // once INT has declined it. Both forms take an optionally signed
            // exponent: 1e3, .5e3 and 0.5e3 are all numbers, as YAML 1.2 says.
            addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.FLOAT, java.util.regex.Pattern.compile(
                  "^(?:[-+]?(?:[0-9][0-9_]*)?\\.[0-9_]*(?:[eE][-+]?[0-9]+)?"
                  + "|[-+]?[0-9][0-9_]*[eE][-+]?[0-9]+"
                  + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$"), "-+0123456789.");
            addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.MERGE,
                  java.util.regex.Pattern.compile("^(?:<<)$"), "<");
            addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.NULL, java.util.regex.Pattern.compile(
                  "^(?:~|null|Null|NULL| )$"), "~nN\0");
            addImplicitResolver(org.yaml.snakeyaml.nodes.Tag.NULL,
                  java.util.regex.Pattern.compile("^$"), null);
            // Tag.TIMESTAMP is deliberately absent: it produced a java.util.Date
            // that JSON then had to render as a string anyway, in a format the
            // document never used.
        }
    }

    /**
     * Re-lays-out YAML one document at a time, so a multi-document file stays a
     * multi-document file.
     *
     * <p>Formatting through {@code jsonToYaml(yamlToJson(input))} turned the
     * stream into a JSON array and rendered it back as a single sequence: a
     * two-manifest Kubernetes file came out as one list, written straight over
     * the editor.
     *
     * @param sortKeys sorts each document's keys; the sort is a JSON-tree
     *                 operation and this is where the tree exists.
     */
    public String formatPreservingDocuments(String yaml, boolean sortKeys) throws Exception {
        String pivot = yamlToJson(yaml);
        JsonNode parsed = jsonMapper.readTree(pivot);
        boolean multi = isMultiDocument(yaml) && parsed.isArray();
        if (!multi) return jsonToYaml(sortKeys ? sortNode(parsed).toString() : pivot);

        StringBuilder out = new StringBuilder();
        for (JsonNode document : parsed) {
            if (!out.isEmpty()) out.append("---\n");
            JsonNode node = sortKeys ? sortNode(document) : document;
            out.append(yamlMapper.writeValueAsString(node));
        }
        return out.toString();
    }

    /**
     * True when the text after the last {@code ---} is blank, i.e. the separator
     * closes the previous document instead of introducing an explicit null one.
     */
    private static boolean endsWithBareSeparator(String yaml) {
        int last = -1;
        String[] lines = yaml.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++)
            if (lines[i].strip().equals("---") || lines[i].strip().startsWith("--- ")) last = i;
        if (last < 0 || !lines[last].strip().equals("---")) return false;
        for (int i = last + 1; i < lines.length; i++)
            if (!lines[i].isBlank()) return false;
        return true;
    }

    /** True when the source actually carries a document separator of its own. */
    private static boolean isMultiDocument(String yaml) {
        for (String line : yaml.split("\r?\n"))
            if (line.strip().equals("---") || line.strip().startsWith("--- ")) return true;
        return false;
    }

    /** Recursively orders object keys; arrays keep their order. */
    private JsonNode sortNode(JsonNode node) {
        if (node.isObject()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            com.fasterxml.jackson.databind.node.ObjectNode out = jsonMapper.createObjectNode();
            for (String name : names) out.set(name, sortNode(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = jsonMapper.createArrayNode();
            for (JsonNode item : node) out.add(sortNode(item));
            return out;
        }
        return node;
    }

    /**
     * Refuses a mapping whose keys are distinct in YAML but identical once
     * stringified for JSON.
     *
     * <p>YAML keys can be any node; JSON keys are strings, so {@code valueToTree}
     * stringifies them. {@code 1} and {@code "1"} — or {@code true} and
     * {@code "true"} — are different keys in YAML and the same key in JSON, and
     * the second silently overwrote the first. Stringifying is fine; losing a
     * value to it is not.
     */
    private static void rejectCollidingKeys(Object document, JsonNode converted) {
        if (converted == null) return;
        // Sequences are descended into as well: a mapping inside a list — every
        // Kubernetes "containers:" block — was never examined, so the guard
        // missed the commonest YAML shape there is.
        if (document instanceof java.util.List<?> list && converted.isArray()) {
            for (int i = 0; i < list.size() && i < converted.size(); i++)
                rejectCollidingKeys(list.get(i), converted.get(i));
            return;
        }
        if (!(document instanceof java.util.Map<?, ?> map) || !converted.isObject()) return;
        if (map.size() != converted.size())
            throw new IllegalArgumentException(
                  "This YAML mapping has keys that differ in YAML but are identical as JSON "
                  + "keys (for example 1 and \"1\"), so converting would drop a value. "
                  + "Give them distinct names first.");
        for (java.util.Map.Entry<?, ?> e : map.entrySet())
            rejectCollidingKeys(e.getValue(), converted.get(String.valueOf(e.getKey())));
    }

    /**
     * Dump-side arguments the five-argument {@link Yaml} constructor demands.
     * Nothing here ever dumps — the composer only loads — so these exist purely
     * to reach the resolver parameter, and are shared rather than reallocated.
     */
    private static final org.yaml.snakeyaml.DumperOptions UNUSED_DUMPER_OPTIONS =
          new org.yaml.snakeyaml.DumperOptions();
    private static final org.yaml.snakeyaml.representer.Representer UNUSED_REPRESENTER =
          new org.yaml.snakeyaml.representer.Representer(UNUSED_DUMPER_OPTIONS);
}
