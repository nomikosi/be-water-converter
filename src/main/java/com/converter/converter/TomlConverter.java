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
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Input JSON must not be empty");
        JsonNode node = jsonMapper.readTree(json);

        // TOML documents are tables: a bare array or scalar root would render
        // as a key-value pair with an EMPTY key (" = [...]"), which is invalid
        // TOML. Wrap them under a named key, mirroring the XML converter.
        if (node.isArray()) {
            node = jsonMapper.createObjectNode().set("items", node);
        } else if (!node.isObject()) {
            node = jsonMapper.createObjectNode().set("value", node);
        }

        return tomlMapper.writeValueAsString(node);
    }
}
