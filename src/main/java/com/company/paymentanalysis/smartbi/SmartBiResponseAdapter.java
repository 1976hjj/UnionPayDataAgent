package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SmartBiResponseAdapter {

    private final ObjectMapper objectMapper;

    public SmartBiResponseAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public QueryResponse normalize(JsonNode response) {
        if (response.has("data") && response.get("data").isArray()) {
            return normalizedResponse(response);
        }
        if (response.has("iterator") && response.get("iterator").isArray()) {
            return dataIteratorResponse(response);
        }
        throw new IllegalStateException("无法识别 SmartBI 返回结构：缺少 data 或 iterator");
    }

    private QueryResponse normalizedResponse(JsonNode response) {
        try {
            return objectMapper.treeToValue(response, QueryResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Mock SmartBI 返回失败", exception);
        }
    }

    private QueryResponse dataIteratorResponse(JsonNode response) {
        List<String> labels = new ArrayList<>();
        response.path("columnLabels").forEach(label -> labels.add(label.asText()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode sourceRow : response.path("iterator")) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < sourceRow.size(); index++) {
                String label = index < labels.size() ? labels.get(index) : "column" + (index + 1);
                row.put(uniqueLabel(row, label), cellValue(sourceRow.get(index)));
            }
            rows.add(row);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "SmartBI DataIterator");
        metadata.put("columnLabels", labels);
        metadata.put("totalRowCount", response.path("totalRowCount").asInt(-1));
        return new QueryResponse("smartbi-data-iterator", rows, metadata);
    }

    private Object cellValue(JsonNode cell) {
        JsonNode value = cell != null && cell.isObject() && cell.has("value")
                ? cell.get("value")
                : cell;
        return value == null || value.isNull()
                ? null
                : objectMapper.convertValue(value, Object.class);
    }

    private String uniqueLabel(Map<String, Object> row, String label) {
        if (!row.containsKey(label)) {
            return label;
        }
        int suffix = 2;
        while (row.containsKey(label + "_" + suffix)) {
            suffix++;
        }
        return label + "_" + suffix;
    }
}
