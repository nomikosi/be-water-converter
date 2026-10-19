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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates Go structs with {@code encoding/json} tags from a JSON structure.
 *
 * <p>Fields are exported, so the package can read and write them, and named the
 * way Go names them, initialisms upper-case ({@code UserID}, {@code URL}); each
 * field's tag holds its key. A key the example showed absent or null in some
 * element is a pointer, or a slice, map or {@code any}, tagged
 * {@code omitempty}; a value seen only as null, or elements of different kinds,
 * is {@code any}. A whole number past 64 bits, or a decimal a float64 cannot
 * hold exactly, is a {@code json.Number}, which keeps its digits.
 */
public class GoStructGenerator {

    /** Name of the generated root struct. */
    public static final String ROOT_TYPE_NAME = "Root";

    /** Words Go writes upper-case in a name, as golint asks. */
    static final Set<String> INITIALISMS = Set.of(
          "acl", "api", "ascii", "cpu", "css", "dns", "eof", "guid", "html", "http", "https", "id",
          "ip", "json", "lhs", "qps", "ram", "rhs", "rpc", "sla", "smtp", "sql", "ssh", "tcp", "tls",
          "ttl", "udp", "ui", "uid", "uri", "url", "utf8", "uuid", "vm", "xml", "xmpp", "xsrf", "xss");

    /** The punctuation a tag name may hold besides letters and digits, as {@code encoding/json} reads one. */
    private static final String TAG_PUNCTUATION = "!#$%&()*+-./:;<=>?@[]^_{|}~ ";

    public String fromJson(String json) throws Exception {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input must not be null or blank");
        StructureModel model = StructureModel.from(GeneratorJson.readTree(json), ROOT_TYPE_NAME,
              key -> goName(key, "Type", "Type"), Set.of());
        StringBuilder body = new StringBuilder();
        boolean[] usesJson = {false};
        for (Map.Entry<String, JsonNode> type : model.types().entrySet()) {
            body.append('\n');
            declare(type.getKey(), type.getValue(), model, body, usesJson);
        }
        StringBuilder out = new StringBuilder("package main\n");
        if (usesJson[0]) out.append("\nimport \"encoding/json\"\n");
        return out.append(body).toString();
    }

    private record Field(String name, String type, String tag, String note) {}

    private static void declare(String name, JsonNode object, StructureModel model, StringBuilder out,
          boolean[] usesJson) {
        if (object.isEmpty()) {
            out.append("type ").append(name).append(" struct{}\n");
            return;
        }
        List<Field> fields = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String key = property.getKey();
            if (!isValidTagName(key)) {
                fields.add(new Field(null, null, null, "The key " + TypeScriptGenerator.stringLiteral(key)
                      + " cannot be named in a struct tag; decode the object into a map[string]any to read it."));
                continue;
            }
            boolean optional = model.isOptional(object, key);
            String type = typeOf(property.getValue(), model, usesJson);
            if (optional && pointable(type)) type = "*" + type;
            // "-" alone tells encoding/json to skip the field; "-," names the key "-".
            String tagName = key.equals("-") ? "-," : key;
            String tag = "`json:\"" + tagName + (optional ? (tagName.endsWith(",") ? "" : ",") + "omitempty" : "")
                  + "\"`";
            fields.add(new Field(SourceConventions.uniqueName(goName(key, "Value", "Field"), "", used), type, tag, null));
        }
        int nameWidth = 0;
        int typeWidth = 0;
        for (Field field : fields) {
            if (field.note() != null) continue;
            nameWidth = Math.max(nameWidth, field.name().length());
            typeWidth = Math.max(typeWidth, field.type().length());
        }
        out.append("type ").append(name).append(" struct {\n");
        for (Field field : fields) {
            if (field.note() != null) {
                out.append("\t// ").append(field.note()).append('\n');
                continue;
            }
            out.append('\t').append(pad(field.name(), nameWidth)).append(' ').append(pad(field.type(), typeWidth))
                  .append(' ').append(field.tag()).append('\n');
        }
        out.append("}\n");
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(width - s.length());
    }

    /** A pointer makes null, and absence, readable: slices, maps and interfaces already hold nil. */
    private static boolean pointable(String type) {
        return !type.startsWith("[]") && !type.startsWith("map[") && !type.equals("any");
    }

    private static String typeOf(JsonNode node, StructureModel model, boolean[] usesJson) {
        if (node.isInt() || node.isShort()) return "int";
        if (node.isLong()) return "int64";
        if (node.isBigInteger() || node.isBigDecimal()) {
            usesJson[0] = true;
            return "json.Number";
        }
        if (node.isNumber()) return "float64";
        if (node.isBoolean()) return "bool";
        if (node.isTextual()) return "string";
        if (node.isObject()) {
            String name = model.nameOf(node);
            return name != null ? name : "map[string]any";
        }
        if (node.isArray()) {
            JsonNode element = model.elementOf(node);
            if (element == null) return "[]any";
            String type = typeOf(element, model, usesJson);
            if (model.hasNullElement(node) && pointable(type)) type = "*" + type;
            return "[]" + type;
        }
        return "any";
    }

    /**
     * An exported Go name from a key's words, initialisms upper-case, a
     * plural one too: {@code imageURLs} is {@code ImageURLs}.
     *
     * @param fallback the name for a key without letters or digits
     * @param prefix   what goes before a name that starts with a digit
     */
    static String goName(String key, String fallback, String prefix) {
        StringBuilder name = new StringBuilder();
        for (String word : SourceConventions.words(key)) {
            String singular = word.endsWith("s") ? word.substring(0, word.length() - 1) : null;
            if (INITIALISMS.contains(word)) name.append(word.toUpperCase(java.util.Locale.ROOT));
            else if (singular != null && INITIALISMS.contains(singular))
                name.append(singular.toUpperCase(java.util.Locale.ROOT)).append('s');
            else name.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        if (name.isEmpty()) return fallback;
        return Character.isDigit(name.charAt(0)) ? prefix + name : name.toString();
    }

    /** Whether {@code encoding/json} reads this key from a tag; otherwise it ignores the tag's name. */
    static boolean isValidTagName(String key) {
        if (key.isEmpty()) return false;
        for (int i = 0; i < key.length(); ) {
            int c = key.codePointAt(i);
            i += Character.charCount(c);
            if (TAG_PUNCTUATION.indexOf(c) < 0 && !Character.isLetter(c) && !Character.isDigit(c)) return false;
        }
        return true;
    }
}
