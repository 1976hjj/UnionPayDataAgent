package com.company.paymentanalysis.smartbi;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public final class SmartBiModels {

    private SmartBiModels() {
    }

    public record Filter(
            String id,
            String name,
            String operation,
            List<String> values) implements Serializable {
    }

    public record RelationNode(
            List<RelationNode> childNodes,
            Filter filter,
            String relation,
            String leaf) implements Serializable {

        public static RelationNode leaf(Filter filter) {
            return new RelationNode(null, filter, null, "true");
        }

        public static RelationNode group(String relation, List<RelationNode> children) {
            return new RelationNode(children, null, relation, "false");
        }
    }

    public record QueryRequest(
            String dataSetId,
            List<String> rows,
            List<String> columns,
            List<Filter> filters,
            RelationNode relationNode,
            List<Sort> sorts) implements Serializable {

        public QueryRequest {
            rows = rows == null ? List.of() : List.copyOf(rows);
            columns = columns == null ? List.of() : List.copyOf(columns);
            filters = filters == null ? List.of() : List.copyOf(filters);
            sorts = sorts == null ? List.of() : List.copyOf(sorts);
        }

        public QueryRequest(
                String dataSetId, List<String> rows, List<String> columns,
                List<Filter> filters, RelationNode relationNode) {
            this(dataSetId, rows, columns, filters, relationNode, List.of());
        }
    }

    public record Sort(String field, String direction) implements Serializable {
    }

    public record QueryResponse(
            String requestId,
            List<Map<String, Object>> data,
            Map<String, Object> metadata) implements Serializable {
    }

    public record QueryTrace(
            String stage, String dimensionCode, QueryRequest request, String sqlPreview) implements Serializable {

        public QueryTrace(String stage, String dimensionCode, QueryRequest request) {
            this(stage, dimensionCode, request, SmartBiSqlPreview.from(request));
        }
    }
}
