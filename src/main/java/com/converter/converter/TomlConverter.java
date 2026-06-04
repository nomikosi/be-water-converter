package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

public class TomlConverter {
    private final ObjectMapper jsonMapper;
    private final TomlMapper   tomlMapper;

    public TomlConverter() {
        jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        tomlMapper = new TomlMapper();
    }

    public String tomlToJson(String toml) throws Exception {
        if (toml == null || toml.isBlank())
            throw new IllegalArgumentException("Input TOML must not be empty");
        JsonNode node = tomlMapper.readTree(toml);
        return jsonMapper.writeValueAsString(node);
    }

    public String jsonToToml(String json) throws Exception {
        JsonNode node = jsonMapper.readTree(json);
        return tomlMapper.writeValueAsString(node);
    }
}
