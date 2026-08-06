package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Converts the documented SmartBI DataIterator response into application table rows. */
@Component
public class SmartBiResponseAdapter {

    private final ObjectMapper objectMapper;

    public SmartBiResponseAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public QueryResponse normalize(JsonNode response) {
        if (response.has("iterator") && response.get("iterator").isArray()) {
            return dataIteratorResponse(response);
        }
        if (response.has("queryResultMetaData")) {
            return augmentedDataSetResponse(response);
        }
        throw new IllegalStateException("无法识别 SmartBI 返回结构：缺少 iterator 或 queryResultMetaData");
    }

    private QueryResponse dataIteratorResponse(JsonNode response) {
        List<String> labels = new ArrayList<>();
        response.path("columnLabels").forEach(label -> labels.add(label.asText()));
        List<Map<String, Object>> rows = rows(response.path("iterator"), labels);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "SmartBI DataIterator");
        metadata.put("columnLabels", labels);
        metadata.put("totalRowCount", response.path("totalRowCount").asInt(-1));
        return new QueryResponse("smartbi-data-iterator", rows, metadata);
    }

    /** Converts AugmentedDataSetForVModule.getData's queryResultMetaData response. */
    private QueryResponse augmentedDataSetResponse(JsonNode response) {
        JsonNode metadataNode = response.path("queryResultMetaData");
        List<String> labels = new ArrayList<>();
        metadataNode.path("queryResultColumnList").forEach(column -> labels.add(columnLabel(column)));
        JsonNode resultRows = findRows(response);
        if (resultRows == null) {
            throw new IllegalStateException("SmartBI getData 返回中缺少结果行");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "SmartBI AugmentedDataSetForVModule.getData");
        metadata.put("columnLabels", labels);
        metadata.put("queryResultMetaData", objectMapper.convertValue(metadataNode, Object.class));
        metadata.put("totalRowCount", totalRowCount(response));
        return new QueryResponse("smartbi-augmented-dataset", rows(resultRows, labels), metadata);
    }

    private List<Map<String, Object>> rows(JsonNode sourceRows, List<String> labels) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode sourceRow : sourceRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (sourceRow.isObject()) {
                sourceRow.fields().forEachRemaining(entry -> row.put(entry.getKey(), cellValue(entry.getValue())));
                rows.add(row);
                continue;
            }
            for (int index = 0; index < sourceRow.size(); index++) {
                String label = index < labels.size() ? labels.get(index) : "column" + (index + 1);
                row.put(uniqueLabel(row, label), cellValue(sourceRow.get(index)));
            }
            rows.add(row);
        }
        return rows;
    }

    private String columnLabel(JsonNode column) {
        if (column.isValueNode()) {
            return column.asText();
        }
        for (String name : List.of("displayName", "columnLabel", "name", "columnName", "fieldName", "id")) {
            if (column.hasNonNull(name) && !column.get(name).asText().isBlank()) {
                return column.get(name).asText();
            }
        }
        return "column";
    }

    private JsonNode findRows(JsonNode response) {
        for (String name : List.of("queryResultData", "queryResultRowList", "data", "rows", "rowList")) {
            JsonNode rows = findRows(response.path(name), false);
            if (rows != null) {
                return rows;
            }
        }
        return findRows(response, true);
    }

    private JsonNode findRows(JsonNode node, boolean recursive) {
        if (node.isArray() && !node.isEmpty() && (node.get(0).isArray() || node.get(0).isObject())) {
            return node;
        }
        if (!recursive || !node.isContainerNode()) {
            return null;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if ("queryResultMetaData".equals(entry.getKey())) {
                    continue;
                }
                JsonNode rows = findRows(entry.getValue(), true);
                if (rows != null) {
                    return rows;
                }
            }
        }
        return null;
    }

    private int totalRowCount(JsonNode response) {
        for (String name : List.of("totalRowCount", "totalCount", "rowCount")) {
            if (response.has(name) && response.get(name).canConvertToInt()) {
                return response.get(name).asInt();
            }
        }
        return -1;
    }

    private Object cellValue(JsonNode cell) {
        JsonNode value = cell != null && cell.isObject() && cell.has("value")
                ? cell.get("value")
                : cell;
        return value == null || value.isNull() ? null : objectMapper.convertValue(value, Object.class);
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
