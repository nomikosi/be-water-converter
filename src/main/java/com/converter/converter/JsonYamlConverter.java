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

    public String yamlToJson(String yaml) throws Exception {
        if (yaml == null || yaml.isBlank())
            throw new IllegalArgumentException("Input YAML must not be empty");
        JsonNode node = yamlMapper.readTree(yaml);
        return jsonMapper.writeValueAsString(node);
    }
}
