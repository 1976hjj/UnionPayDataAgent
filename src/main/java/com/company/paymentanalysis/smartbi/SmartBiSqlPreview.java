package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import java.util.ArrayList;
import java.util.List;

public final class SmartBiSqlPreview {

    private SmartBiSqlPreview() {
    }

    public static String from(QueryRequest request) {
        List<String> predicates;
        if (request.relationNode() != null) {
            predicates = List.of(relationSql(request.relationNode()));
        } else {
            predicates = request.filters() == null
                    ? List.of()
                    : request.filters().stream().map(SmartBiSqlPreview::filterSql).toList();
        }
        return build(request.dataSetId(), request.rows(), request.columns(), predicates);
    }

    public static String build(
            String dataSetId, List<String> dimensions, List<String> metrics, List<String> predicates) {
        List<String> selections = new ArrayList<>(dimensions);
        metrics.forEach(field -> selections.add(aggregation(field) + "(" + field + ") AS " + field));
        StringBuilder sql = new StringBuilder("-- 等价 SQL 预览，SmartBI 实际执行以 JSON 查询计划为准\n");
        sql.append("-- dataSetId: ").append(dataSetId).append("\nSELECT\n  ");
        sql.append(String.join(",\n  ", selections)).append("\nFROM smartbi_dataset");
        if (!predicates.isEmpty()) {
            sql.append("\nWHERE ").append(String.join("\n  AND ", predicates));
        }
        if (!dimensions.isEmpty()) {
            sql.append("\nGROUP BY ").append(String.join(", ", dimensions));
        }
        return sql.append(";").toString();
    }

    private static String filterSql(Filter filter) {
        List<String> values = filter.values() == null ? List.of() : filter.values();
        return switch (filter.operation()) {
            case "BETWEEN" -> values.size() >= 2
                    ? filter.name() + " BETWEEN " + quote(values.get(0)) + " AND " + quote(values.get(1))
                    : filter.name() + " BETWEEN NULL AND NULL";
            case "IN" -> filter.name() + " IN ("
                    + String.join(", ", values.stream().map(SmartBiSqlPreview::quote).toList()) + ")";
            case "EQ", "EQUALS", "=" -> filter.name() + " = "
                    + (values.isEmpty() ? "NULL" : quote(values.get(0)));
            default -> filter.name() + " " + filter.operation() + " "
                    + (values.isEmpty() ? "NULL" : quote(values.get(0)));
        };
    }

    private static String relationSql(RelationNode node) {
        if (node == null) {
            return "1 = 1";
        }
        if (node.filter() != null) {
            return filterSql(node.filter());
        }
        List<RelationNode> children = node.childNodes() == null ? List.of() : node.childNodes();
        if (children.isEmpty()) {
            return "1 = 1";
        }
        String relation = node.relation() == null ? "AND" : node.relation().toUpperCase();
        return "(" + String.join(
                " " + relation + " ",
                children.stream().map(SmartBiSqlPreview::relationSql).toList()) + ")";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String aggregation(String field) {
        return field.toLowerCase().contains("rate") ? "AVG" : "SUM";
    }
}
