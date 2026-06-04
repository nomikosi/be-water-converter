package com.converter.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.util.*;

public class CsvConverter {
    private final ObjectMapper jsonMapper;
    private final CsvMapper    csvMapper;

    public CsvConverter() {
        jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        csvMapper  = new CsvMapper();
    }

    public String csvToJson(String csv) throws Exception {
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        MappingIterator<Map<String, String>> it =
              csvMapper.readerFor(Map.class).with(schema).readValues(csv);
        return jsonMapper.writeValueAsString(it.readAll());
    }

    public String jsonToCsv(String json) throws Exception {
        JsonNode root = jsonMapper.readTree(json);
        LinkedHashSet<String> headers = new LinkedHashSet<>();

        if (root.isObject()) {
            ArrayNode arr = jsonMapper.createArrayNode();
            arr.add(root);
            root = arr;
        }
        if (!root.isArray())
            throw new IllegalArgumentException(
                  "JSON must be an array of objects or a single object for CSV output");

        for (JsonNode row : root) row.fieldNames().forEachRemaining(headers::add);

        CsvSchema.Builder sb = CsvSchema.builder().setUseHeader(true);
        for (String h : headers) sb.addColumn(h);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : root) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String h : headers) {
                JsonNode val = row.get(h);
                map.put(h, val == null ? "" : (val.isTextual() ? val.asText() : val.toString()));
            }
            rows.add(map);
        }
        return csvMapper.writer(sb.build()).writeValueAsString(rows);
    }
}
