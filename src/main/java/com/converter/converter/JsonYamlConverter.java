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

public class JsonYamlConverter {
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;

    public JsonYamlConverter() {
        jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

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
     */
    public String yamlToJson(String yaml) throws Exception {
        if (yaml == null || yaml.isBlank())
            throw new IllegalArgumentException("Input YAML must not be empty");
        java.util.List<JsonNode> docs =
              yamlMapper.readerFor(JsonNode.class).<JsonNode>readValues(yaml).readAll();
        // Trailing/empty documents surface as null, NullNode or a blank text
        // scalar depending on parser configuration — drop them all.
        docs.removeIf(d -> d == null || d.isNull() || d.isMissingNode()
              || (d.isTextual() && d.asText().isBlank()));
        if (docs.isEmpty())
            throw new IllegalArgumentException("Input YAML contains no documents");
        JsonNode node = docs.size() == 1
              ? docs.get(0)
              : jsonMapper.createArrayNode().addAll(docs);
        return jsonMapper.writeValueAsString(node);
    }
}
