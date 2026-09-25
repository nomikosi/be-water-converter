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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CommentEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class JsonYamlConverter {

    /**
     * SnakeYAML's default code-point limit is ~3 MB, which rejected YAML files
     * well under the plugin's own 10 MB open warning. Raised to match, leaving
     * the alias and nesting limits at their defaults so billion-laughs input is
     * still refused.
     */
    static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    // ── Scalar resolution, shared by the reader and the writer ────────────
    //
    // These are the implicit-tag rules this converter reads YAML by: YAML 1.2's
    // core schema minus the YAML 1.1 rules that rewrite data (see
    // CoreScalarResolver). The writer applies the same patterns to decide what
    // to quote, so a string is quoted exactly when a bare scalar of that text
    // would be read back as something other than a string. Keeping one set of
    // patterns is what stops the two sides disagreeing: "0x1F" and "1e3" were
    // emitted bare and came back as 31 and 1000.0.
    //
    // Named CORE_* on purpose. SnakeYAML's Resolver declares public static
    // BOOL, INT, FLOAT, MERGE, NULL and EMPTY of its own, and inside the
    // subclass below an unqualified INT is the INHERITED one: a first version
    // of this shared the short names and silently registered YAML 1.1's rules.

    // YAML 1.2's booleans only. Under 1.1 yes/no/on/off were booleans as well,
    // which turned the "on:" key of every GitHub Actions workflow into "true"
    // and a value of no into false — the Norway problem. Modern parsers read
    // them as the text they are, and so does this.
    private static final Pattern CORE_BOOL = Pattern.compile(
          "^(?:true|True|TRUE|false|False|FALSE)$");

    // Neither the sexagesimal "[-+]?[1-9][0-9_]*(:[0-5]?[0-9])+" nor bare
    // octal "0[0-7_]+", so 12:30:00 and 0777 stay the text they were written
    // as. YAML 1.2's explicit 0o777 is deliberately absent too: SafeConstructor
    // reads a leading 0 as octal and then calls parseInt("o777", 8), so tagging
    // it INT throws rather than converts. Unresolved, it is simply the string
    // it looks like.
    private static final Pattern CORE_INT = Pattern.compile(
          "^(?:[-+]?0b[0-1_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+)$");

    // A dot or an exponent is required, so 0777 cannot land here either once
    // INT has declined it, and a digit is required on at least one side of the
    // dot: the previous form accepted a lone ".", "-." and "._", tagged them
    // FLOAT, and construction then threw NumberFormatException at a value that
    // is simply the text it looks like. Both forms take an optionally signed
    // exponent: 1e3, .5e3 and 0.5e3 are all numbers, as YAML 1.2 says.
    private static final Pattern CORE_FLOAT = Pattern.compile(
          "^(?:[-+]?(?:[0-9][0-9_]*\\.[0-9_]*|\\.[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)?"
          + "|[-+]?[0-9][0-9_]*[eE][-+]?[0-9]+"
          + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");

    private static final Pattern CORE_MERGE = Pattern.compile("^(?:<<)$");
    private static final Pattern CORE_NULL  = Pattern.compile("^(?:~|null|Null|NULL| )$");
    private static final Pattern CORE_EMPTY = Pattern.compile("^$");

    /** True when a bare scalar of this text would be read as something other than a string. */
    static boolean resolvesToNonString(String text) {
        return text.isEmpty()
              || CORE_BOOL.matcher(text).matches()
              || CORE_INT.matcher(text).matches()
              || CORE_FLOAT.matcher(text).matches()
              || CORE_NULL.matcher(text).matches()
              || CORE_MERGE.matcher(text).matches();
    }

    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;

    public JsonYamlConverter() {
        // No INDENT_OUTPUT: writes the internal pivot only, which is re-parsed.
        jsonMapper = PivotJson.mapper()
              // SnakeYAML resolves YAML timestamps to java.util.Date; without
              // this they would serialise as epoch millis rather than the text
              // the document actually contained.
              .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // YAML keys can be any scalar and JSON keys are strings: 1 becomes "1"
        // and true becomes "true" through valueToTree, but null is the one key
        // Jackson refuses, with "Null key for a Map not allowed in JSON (use a
        // converting NullKeySerializer?)" as the user's error. The same text
        // rule applies, and rejectCollidingKeys still catches a literal "null"
        // key beside it.
        jsonMapper.getSerializerProvider().setNullKeySerializer(
              new com.fasterxml.jackson.databind.JsonSerializer<>() {
                  @Override public void serialize(Object value,
                        com.fasterxml.jackson.core.JsonGenerator gen,
                        com.fasterxml.jackson.databind.SerializerProvider provider)
                        throws java.io.IOException {
                      gen.writeFieldName("null");
                  }
              });

        yamlMapper = YAMLMapper.builder(
              YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)  // suppress "---"
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)           // bare strings, no 'quoting'
                    // On its own MINIMIZE_QUOTES emitted the STRING "123" as
                    // bare 123, which YAML reads back as a number: the round
                    // trip retyped the value. This quotes the numeric-looking
                    // ones and leaves ordinary text bare, which is what keeps
                    // the output readable — the point of MINIMIZE_QUOTES.
                    .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                    // That feature knows plain decimals only. The reader also
                    // resolves hex, binary, exponent and underscore forms, so
                    // "0x1F", "1e3" and "1_000" were emitted bare and came back
                    // as numbers. Deciding quotes by the reader's own rules
                    // closes the gap without quoting every string.
                    .stringQuotingChecker(new ResolverAwareQuoting())
                    .build()
        ).build();
    }

    /**
     * Quotes what Jackson's default checker quotes, plus every string that
     * {@link CoreScalarResolver} would read back as a non-string.
     */
    private static final class ResolverAwareQuoting extends StringQuotingChecker.Default {
        @Override public boolean needToQuoteName(String name) {
            return super.needToQuoteName(name) || resolvesToNonString(name);
        }

        @Override public boolean needToQuoteValue(String value) {
            return super.needToQuoteValue(value) || resolvesToNonString(value);
        }
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
     */
    public String yamlToJson(String yaml) throws Exception {
        List<JsonNode> docs = loadDocuments(yaml);
        JsonNode node = docs.size() == 1
              ? docs.get(0)
              : jsonMapper.createArrayNode().addAll(docs);
        return jsonMapper.writeValueAsString(node);
    }

    /**
     * Every document in the stream as a JSON tree, in order.
     *
     * <p>Parsed through SnakeYAML's composer rather than Jackson's YAML parser:
     * Jackson works at the event level and never resolves anchors, so {@code *ref}
     * arrived as the literal string {@code "ref"} and a {@code <<:} merge key
     * survived as a key of that name with the merged content discarded.
     *
     * <p>This is also the one place that knows how many documents there are.
     * Format used to decide that by scanning the text for {@code ---} lines,
     * which also matched the optional start marker of a single document: a
     * {@code ---}-prefixed list was split into one document per element.
     */
    private List<JsonNode> loadDocuments(String yaml) { return loadDocuments(yaml, false); }

    private List<JsonNode> loadDocuments(String yaml, boolean formatting) {
        if (yaml == null || yaml.isBlank())
            throw new IllegalArgumentException("Input YAML must not be empty");

        List<JsonNode> docs = new ArrayList<>();
        for (Object document : composer(formatting).loadAll(ConversionPipeline.stripBom(yaml))) {
            rejectRunawayAliases(document, yaml.length());
            if (formatting) rejectNonStringKeys(document,
                  java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
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
        // Construction maps both empty documents and explicit null scalars to
        // Java null. Inspect scalar events only for this ambiguous case, so a
        // document written as "null", "~" or "!!null ''" remains convertible.
        if (docs.isEmpty() || (docs.stream().allMatch(JsonNode::isNull) && !hasExplicitScalar(yaml)))
            throw new IllegalArgumentException("Input YAML contains no documents");
        return docs;
    }

    private boolean hasExplicitScalar(String yaml) {
        for (Event event : composer().parse(new StringReader(ConversionPipeline.stripBom(yaml)))) {
            if (event instanceof org.yaml.snakeyaml.events.ScalarEvent scalar
                  && (!scalar.getValue().isEmpty() || scalar.getTag() != null)) return true;
        }
        return false;
    }

    /**
     * Values an alias-built document may expand to before it is refused. A
     * document without aliases cannot get near this without being tens of
     * megabytes, which the open-file warning already covers.
     */
    static final long MAX_EXPANDED_VALUES = 2_000_000;

    /**
     * Refuses the two things aliases can do that JSON cannot follow.
     *
     * <p>SnakeYAML builds the graph with shared references, so it is small in
     * memory whatever the aliases say; it is {@code valueToTree} that copies
     * every alias out into a tree. Its alias limit counts aliases, not what
     * they expand to: ten anchors each referring ten times to the previous one
     * stay well under it and expand to ten billion values. And an anchor that
     * contains its own alias is a cycle, which the copy followed until the
     * stack overflowed.
     */
    private static void rejectRunawayAliases(Object document, int sourceLength) {
        java.util.IdentityHashMap<Object, Long> sizes = new java.util.IdentityHashMap<>();
        java.util.Set<Object> open = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long[] distinct = {0};
        long expanded = expandedSize(document, sizes, open, distinct);
        // Only alias expansion is refused: a plain document this large is the
        // user's own data, and the factor-of-two test is what tells them apart.
        if (expanded > MAX_EXPANDED_VALUES && expanded > 2 * distinct[0])
            throw new IllegalArgumentException(String.format(
                  "This YAML expands to over %,d values through its aliases (%,d were written in a "
                  + "%,d-character document), which is too many to convert. Reduce the aliasing, "
                  + "or convert the anchored parts separately.",
                  MAX_EXPANDED_VALUES, distinct[0], sourceLength));
    }

    /**
     * Values in the fully expanded tree, memoised per container so a shared
     * anchor is measured once and charged at every alias. {@code distinct}
     * accumulates what was actually written, for the ratio above.
     */
    private static long expandedSize(Object node, java.util.IdentityHashMap<Object, Long> sizes,
          java.util.Set<Object> open, long[] distinct) {
        boolean container = node instanceof java.util.Map<?, ?> || node instanceof List<?>;
        if (!container) {
            distinct[0]++;
            return 1;
        }
        Long known = sizes.get(node);
        if (known != null) return known;
        if (!open.add(node))
            throw new IllegalArgumentException(
                  "This YAML refers to itself: an alias points at an anchor that contains it. "
                  + "JSON has no way to write a cycle, so the document cannot be converted.");
        distinct[0]++;
        long total = 1;
        Iterable<?> children = node instanceof java.util.Map<?, ?> map ? map.values() : (List<?>) node;
        for (Object child : children) total += expandedSize(child, sizes, open, distinct);
        open.remove(node);
        sizes.put(node, total);
        return total;
    }

    private static Yaml composer() { return composer(false); }

    private static Yaml composer(boolean formatting) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        // A repeated key silently kept only the last value. YAML says duplicate
        // keys are an error; SnakeYAML merely defaults to allowing them.
        options.setAllowDuplicateKeys(false);
        // SafeConstructor refuses arbitrary Java type tags, so a hostile
        // document cannot cause class instantiation.
        return new Yaml(new ExactFloatConstructor(options, formatting),
              UNUSED_REPRESENTER, UNUSED_DUMPER_OPTIONS, options, new CoreScalarResolver());
    }

    /**
     * {@link SafeConstructor} with floats built as {@link BigDecimal} from
     * their text rather than as {@code double}.
     *
     * <p>Through double, {@code 1.10} became {@code 1.1}, {@code 1e400} became
     * the string {@code "Infinity"} and a long decimal was cut to 17 digits —
     * the same losses the JSON reader was already fixed for, and Format wrote
     * {@code price: 1.1} back over a document that said {@code 1.10}. The pivot
     * carries BigDecimal exactly, so this only has to hand it one.
     *
     * <p>{@code .inf} and {@code .nan} have no BigDecimal form and keep
     * SnakeYAML's own construction; JSON cannot carry them either, so they
     * render as the strings {@code "Infinity"} and {@code "NaN"}, and
     * {@link #formatPreservingDocuments} refuses to write that back.
     */
    private static final class ExactFloatConstructor extends SafeConstructor {
        private static final java.util.Set<Tag> FORMATTABLE_TAGS = java.util.Set.of(
              Tag.MAP, Tag.SEQ, Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.BINARY);
        private final boolean formatting;

        ExactFloatConstructor(LoaderOptions options, boolean formatting) {
            super(options);
            this.formatting = formatting;
            yamlConstructors.put(Tag.FLOAT, new ConstructExactFloat());
        }

        @Override protected Object constructObject(Node node) {
            if (formatting && !FORMATTABLE_TAGS.contains(node.getTag()))
                throw new IllegalArgumentException(
                      "Format cannot preserve the YAML type " + node.getTag().getValue()
                      + " through JSON. The document is left as it is.");
            return super.constructObject(node);
        }

        private final class ConstructExactFloat extends AbstractConstruct {
            private final ConstructYamlFloat nonFinite = new ConstructYamlFloat();

            @Override public Object construct(Node node) {
                String text = constructScalar((ScalarNode) node).replace("_", "");
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.endsWith("inf") || lower.endsWith("nan")) return nonFinite.construct(node);
                try {
                    return new BigDecimal(text);
                } catch (NumberFormatException notADecimal) {
                    return nonFinite.construct(node);
                }
            }
        }
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
     * <p>This drops exactly those, plus the yes/no/on/off booleans: null, plain
     * integers, floats, merge keys and {@code 0x}/{@code 0b} forms all still
     * resolve, and true/false are still booleans. Tag.TIMESTAMP is deliberately
     * absent: it produced a java.util.Date that JSON then had to render as a
     * string anyway, in a format the document never used.
     */
    private static final class CoreScalarResolver extends org.yaml.snakeyaml.resolver.Resolver {
        @Override protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL,  CORE_BOOL,  "yYnNtTfFoO");
            addImplicitResolver(Tag.INT,   CORE_INT,   "-+0123456789");
            addImplicitResolver(Tag.FLOAT, CORE_FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, CORE_MERGE, "<");
            addImplicitResolver(Tag.NULL,  CORE_NULL,  "~nN\0");
            addImplicitResolver(Tag.NULL,  CORE_EMPTY, null);
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
        List<JsonNode> docs = loadDocuments(yaml, true);
        for (JsonNode document : docs) rejectNonFinite(document);

        StringBuilder out = new StringBuilder();
        for (JsonNode document : docs) {
            if (!out.isEmpty()) out.append("---\n");
            out.append(yamlMapper.writeValueAsString(sortKeys ? JsonTrees.sorted(document) : document));
        }
        return out.toString();
    }

    /**
     * Refuses to format a document carrying {@code .inf} or {@code .nan}: the
     * JSON tree in between can only hold them as the strings "Infinity" and
     * "NaN", so Format would have quietly turned a number into text.
     */
    private static void rejectNonFinite(JsonNode node) {
        if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue()))
            throw new IllegalArgumentException(
                  "Format would rewrite a .inf or .nan value as the text \"" + node.asText()
                  + "\": the JSON step this uses has no infinity or NaN. "
                  + "The document is left as it is.");
        for (JsonNode child : node) rejectNonFinite(child);
    }

    /**
     * What Format would silently discard from this document: comments, and
     * anchors (whose aliases and merge keys come back expanded in place).
     * Returns a description with nothing in it when the document carries
     * neither, and when it cannot be parsed at all — {@link #yamlToJson} then
     * reports the real error.
     */
    public ConversionPipeline.FormatLosses countFormatLosses(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setProcessComments(true);
        Yaml parser = new Yaml(new SafeConstructor(options),
              UNUSED_REPRESENTER, UNUSED_DUMPER_OPTIONS, options);
        int comments = 0, anchors = 0;
        try {
            // Events only: nothing is constructed, so this costs a parse and
            // holds no document in memory.
            for (Event event : parser.parse(new StringReader(ConversionPipeline.stripBom(yaml)))) {
                if (event instanceof CommentEvent comment) {
                    if (comment.getCommentType() != CommentType.BLANK_LINE) comments++;
                } else if (event instanceof NodeEvent node
                      && !(event instanceof AliasEvent) && node.getAnchor() != null) {
                    anchors++;
                }
            }
        } catch (RuntimeException notParseable) {
            return new ConversionPipeline.FormatLosses(0, 0);
        }
        return new ConversionPipeline.FormatLosses(comments, anchors);
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

    /** JSON only carries string keys; Format must not change YAML key types. */
    private static void rejectNonStringKeys(Object value, java.util.Set<Object> seen) {
        if (value instanceof java.util.Map<?, ?> map) {
            if (!seen.add(value)) return;
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String))
                    throw new IllegalArgumentException(
                          "Format cannot preserve a non-string YAML mapping key through JSON. "
                          + "The document is left as it is.");
                rejectNonStringKeys(entry.getValue(), seen);
            }
        } else if (value instanceof List<?> list && seen.add(value)) {
            for (Object child : list) rejectNonStringKeys(child, seen);
        }
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
        if (document instanceof List<?> list && converted.isArray()) {
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
