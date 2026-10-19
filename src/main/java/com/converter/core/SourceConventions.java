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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The JSON-to-source decisions every code generator makes the same way: which
 * identifier a key becomes, which temporal type an ISO-8601 example implies,
 * and how a key survives being embedded in a string literal.
 *
 * <p>Only the reserved words and the legal identifier characters actually
 * differ between target languages, so those are parameters rather than copies.
 * Keeping the rest here is what stops the generators drifting: the Java and
 * Kotlin copies of this logic had already disagreed on what an unusable key
 * becomes.
 *
 * @see StructureModel for the other half of the shared work — which types a
 *      document implies, and what each is named.
 */
public final class SourceConventions {

    private SourceConventions() {}

    /** Allocates a name in a scope, suffixing collisions from 2 onward. */
    public static String uniqueName(String base, String separator, Set<String> used) {
        if (used.add(base)) return base;
        int suffix = 2;
        while (!used.add(base + separator + suffix)) suffix++;
        return base + separator + suffix;
    }

    /**
     * Name for a key that sanitises down to nothing usable. It cannot be an
     * underscore run: {@code _} is a reserved keyword in Java 9+, and Kotlin
     * reserves {@code _}, {@code __}, {@code ___} and so on all the way up, so
     * the obvious fallback does not compile in either language.
     */
    public static final String FALLBACK_NAME = "_value";

    /** {@code _}, {@code __}, {@code ___}, … — reserved rather than identifiers. */
    private static final Pattern ONLY_UNDERSCORES = Pattern.compile("_+");

    /** Segment separators in a JSON key, each starting a new camelCase word. */
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[_\\-.]+");

    // ── ISO-8601 date/time detection ──────────────────────────────────────
    private static final Pattern ISO_DATE =
          Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ISO_DATETIME_OFFSET =
          Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})");
    private static final Pattern ISO_DATETIME_LOCAL =
          Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");

    /**
     * The {@code java.time} type an ISO-8601 value implies, or null when the
     * value is not a date. Both target languages use the same type names.
     *
     * <p>A pattern match is confirmed with a real parse, so "2025-13-99" stays
     * a string rather than becoming an unparseable {@code LocalDate}.
     */
    public static String temporalTypeFor(String value) {
        try {
            if (ISO_DATETIME_OFFSET.matcher(value).matches()) {
                java.time.OffsetDateTime.parse(value);
                return "OffsetDateTime";
            }
            if (ISO_DATETIME_LOCAL.matcher(value).matches()) {
                java.time.LocalDateTime.parse(value);
                return "LocalDateTime";
            }
            if (ISO_DATE.matcher(value).matches()) {
                java.time.LocalDate.parse(value);
                return "LocalDate";
            }
        } catch (java.time.format.DateTimeParseException notADate) {
            return null;
        }
        return null;
    }

    /**
     * Turns a JSON key into a camelCase identifier legal in the target
     * language. The result never needs quoting or back-quoting: where the name
     * had to change, the caller pairs it with {@code @JsonProperty} carrying
     * the original key.
     *
     * @param illegalChars characters that cannot appear in an identifier, each
     *                     replaced by an underscore. Java permits {@code $} and
     *                     Kotlin does not, which is the whole difference.
     * @param keywords     reserved words, which are suffixed rather than
     *                     quoted so the generated source stays readable.
     */
    public static String toCamelCase(String key, Pattern illegalChars, Set<String> keywords) {
        if (key == null || key.isEmpty()) return FALLBACK_NAME;
        StringBuilder sb = new StringBuilder();
        for (String rawPart : KEY_SEPARATORS.split(key)) {
            if (rawPart.isEmpty()) continue;
            String part = illegalChars.matcher(rawPart).replaceAll("_");
            if (sb.isEmpty()) {
                sb.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        String result = sb.toString();
        // A key of only separators ("__") leaves nothing; one of only illegal
        // characters leaves an underscore per character ("@" -> "_", a CJK word
        // -> "__"). Every underscore run is reserved in Kotlin, so none of them
        // is a name.
        if (result.isEmpty() || ONLY_UNDERSCORES.matcher(result).matches()) return FALLBACK_NAME;
        if (Character.isDigit(result.charAt(0))) result = "_" + result;
        return keywords.contains(result) ? result + "Value" : result;
    }

    /**
     * A key's words, lower-cased: its runs of ASCII letters and digits, split
     * where the case turns as camelCase and PascalCase turn it. {@code userID}
     * is {@code user id}, {@code HTTPServer} {@code http server}, and
     * {@code first-name} {@code first name}. Other characters only separate
     * words: the generators that build names from words keep the key itself
     * in an annotation or a tag.
     */
    public static java.util.List<String> words(String key) {
        java.util.List<String> words = new java.util.ArrayList<>();
        if (key == null) return words;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean letterOrDigit = c < 0x80 && Character.isLetterOrDigit(c);
            if (letterOrDigit && !word.isEmpty()) {
                char previous = key.charAt(i - 1);
                boolean upper = Character.isUpperCase(c);
                boolean turn = upper && (Character.isLowerCase(previous) || Character.isDigit(previous))
                      // The last capital of an acronym starts the next word,
                      // HTTPServer, unless an s makes the acronym plural: userIDs.
                      || upper && Character.isUpperCase(previous) && i + 1 < key.length()
                            && Character.isLowerCase(key.charAt(i + 1)) && !pluralS(key, i + 1);
                if (turn) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            }
            if (letterOrDigit) {
                word.append(Character.toLowerCase(c));
            } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) words.add(word.toString());
        return words;
    }

    /** An {@code s} at {@code i} that ends its word: what follows is no lower-case letter. */
    private static boolean pluralS(String key, int i) {
        return key.charAt(i) == 's' && (i + 1 == key.length() || !Character.isLowerCase(key.charAt(i + 1)));
    }

    /** {@code first_name}: the words joined by underscores; empty when the key has none. */
    public static String snakeCase(String key) {
        return String.join("_", words(key));
    }

    /** {@code FirstName}: every word capitalized; empty when the key has none. */
    public static String pascalCase(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : words(key)) out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        return out.toString();
    }

    /** {@code firstName}: the first word lower-case, the rest capitalized; empty when the key has none. */
    public static String lowerCamelCase(String key) {
        String pascal = pascalCase(key);
        return pascal.isEmpty() ? pascal : Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * A type name built from a key's words, for the generators that name
     * types that way: {@code Type} for a key without any, and {@code Type}
     * before one that starts with a digit.
     */
    public static String typeNameFromWords(String key) {
        String name = pascalCase(key);
        return name.isEmpty() ? "Type" : Character.isDigit(name.charAt(0)) ? "Type" + name : name;
    }

    /**
     * Upper-cases the first letter, for turning a field name into a type name.
     * Leading underscores are skipped rather than given up on, so the names
     * this class invents still read as types: {@code _value} -> {@code _Value}.
     */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        int i = 0;
        while (i < s.length() && s.charAt(i) == '_') i++;
        if (i == s.length()) return s;
        return s.substring(0, i) + Character.toUpperCase(s.charAt(i)) + s.substring(i + 1);
    }

    /**
     * True when {@code @JsonProperty} can actually carry this key. It cannot
     * carry the empty one: Jackson reads an empty value as USE_DEFAULT_NAME, so
     * {@code @JsonProperty("")} silently binds to the field's own name instead
     * of the key it came from, and the generated class cannot read the document
     * it was generated from. Empty keys arrive mostly from XML, where the mapper
     * files element text content under "".
     */
    /**
     * True when Jackson would give the property behind this name's accessors a
     * different name. An accessor is {@code get} plus the name with its first
     * letter capitalised, and Jackson reads a property name back by
     * lower-casing the capitals it starts with: {@code xAxis} becomes
     * {@code getXAxis}, which Jackson reads as {@code xaxis}, and {@code iD}
     * becomes {@code getID}, read as {@code id} — the same name as an
     * {@code id} beside it. Classes holding such a name bind through their
     * fields instead; see {@link #FIELD_BINDING}.
     */
    public static boolean accessorNameDiffers(String name) {
        return name.length() > 1 && Character.isLetter(name.charAt(0)) && Character.isUpperCase(name.charAt(1));
    }

    /**
     * The annotation arguments that make Jackson bind a class through its
     * fields, by the fields' own names, and ignore its accessors. Valid Java
     * and Kotlin alike.
     */
    public static final String FIELD_BINDING = "fieldVisibility = JsonAutoDetect.Visibility.ANY,\n"
          + "        getterVisibility = JsonAutoDetect.Visibility.NONE,\n"
          + "        isGetterVisibility = JsonAutoDetect.Visibility.NONE,\n"
          + "        setterVisibility = JsonAutoDetect.Visibility.NONE";

    public static boolean isMappableKey(String key) {
        return key != null && !key.isEmpty();
    }

    /**
     * Stands in for the annotation on a key {@link #isMappableKey} rejects. It
     * points at {@code @JacksonXmlText} rather than just refusing, because the
     * commonest way to get an empty key is XML element text, and that annotation
     * does bind it.
     */
    public static final String UNMAPPABLE_KEY_NOTE =
          "source key is empty; @JsonProperty cannot express that "
          + "— for XML element text use @JacksonXmlText";

    /** Escapes a JSON key for embedding in a Java string literal. */
    public static String javaStringLiteral(String value) {
        return escape(value, false);
    }

    /**
     * Escapes a JSON key for embedding in a Kotlin string literal. Kotlin
     * additionally reads {@code $} as the start of a string template.
     */
    public static String kotlinStringLiteral(String value) {
        return escape(value, true);
    }

    /**
     * Control characters matter as much as quotes here: a raw newline ends the
     * literal in both languages, so a key carrying one produced source that
     * did not compile. Anything else below the printable range is written as a
     * {@code \\uXXXX} escape, which both languages accept — and which is safe
     * in Java precisely because the two escapes Java's lexer would turn back
     * into line terminators, {@code \\n} and {@code \\r}, are handled above.
     */
    private static String escape(String value, boolean dollarStartsTemplate) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"'  -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '$'  -> out.append(dollarStartsTemplate ? "\\$" : "$");
                default   -> {
                    if (c < 0x20 || c == 0x7F) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
