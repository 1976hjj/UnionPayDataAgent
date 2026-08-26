package com.company.paymentanalysis.artifact.model;

import java.util.List;
import java.util.Map;

/** Typed, reusable query output. Unlike the legacy response, numeric values stay numeric. */
public record QueryResultArtifactPayload(
        String summary,
        List<Column> columns,
        List<Map<String, Object>> rows,
        long totalRows,
        boolean truncated,
        QueryContract queryContract) {

    public QueryResultArtifactPayload {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public record Column(
            String id,
            String displayName,
            Role role,
            DataType dataType,
            String unit) {
    }

    public enum Role { DIMENSION, METRIC }

    public enum DataType { STRING, NUMBER, DATE, DATETIME, BOOLEAN }

    public record QueryContract(
            String dataSetId,
            List<String> metricIds,
            List<String> dimensionIds,
            List<Filter> filters,
            List<Sort> sorts) {

        public QueryContract {
            metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
            dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
            filters = filters == null ? List.of() : List.copyOf(filters);
            sorts = sorts == null ? List.of() : List.copyOf(sorts);
        }
    }

    public record Filter(String fieldId, String operator, List<String> values) {
        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record Sort(String fieldId, String direction) {
    }
}
