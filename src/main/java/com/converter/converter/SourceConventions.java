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
