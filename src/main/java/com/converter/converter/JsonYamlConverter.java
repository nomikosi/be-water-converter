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
        jsonMapper = new ObjectMapper()
              // SnakeYAML resolves YAML timestamps to java.util.Date; without
              // this they would serialise as epoch millis rather than the text
              // the document actually contained.
              .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        yamlMapper = YAMLMapper.builder(
              YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)  // suppress "---"
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)           // bare strings, no 'quoting'
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
        // SafeConstructor refuses arbitrary Java type tags, so a hostile
        // document cannot cause class instantiation.
        Yaml composer = new Yaml(new SafeConstructor(options));

        java.util.List<JsonNode> docs = new java.util.ArrayList<>();
        for (Object document : composer.loadAll(ConversionPipeline.stripBom(yaml))) {
            if (document == null) continue;      // empty or "---"-only document
            JsonNode node = jsonMapper.valueToTree(document);
            if (node == null || node.isNull() || node.isMissingNode()) continue;
            if (node.isTextual() && node.asText().isBlank()) continue;
            docs.add(node);
        }
        if (docs.isEmpty())
            throw new IllegalArgumentException("Input YAML contains no documents");

        JsonNode node = docs.size() == 1
              ? docs.get(0)
              : jsonMapper.createArrayNode().addAll(docs);
        return jsonMapper.writeValueAsString(node);
    }
}
