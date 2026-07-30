package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.ComparisonResult;
import com.company.paymentanalysis.calculation.RankingItem;
import com.company.paymentanalysis.calculation.RankingResult;
import com.company.paymentanalysis.calculation.TrendPoint;
import com.company.paymentanalysis.calculation.TrendSummary;
import com.company.paymentanalysis.controller.ChatQueryController.QueryResult;
import com.company.paymentanalysis.controller.ChatQueryController.ResultColumn;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.formatter.AnalysisResultFormatterRegistry;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ChatAnalysisResultAdapter {

    private final AnalysisResultFormatterRegistry formatterRegistry;

    public ChatAnalysisResultAdapter(AnalysisResultFormatterRegistry formatterRegistry) {
        this.formatterRegistry = formatterRegistry;
    }

    public AdaptedAnalysisResult adapt(AnalysisExecutionResult execution, QueryPlan plan) {
        String summary = formatterRegistry.format(execution, plan);
        QueryResult result = switch (execution.intent()) {
            case SINGLE_QUERY, GROUP_QUERY -> standardResult(execution, summary, plan);
            case COMPARE_QUERY -> comparisonResult(execution, summary, plan);
            case TREND_QUERY -> trendResult(execution, summary, plan);
            case RANK_QUERY -> rankingResult(execution, summary, plan);
            default -> null;
        };
        return new AdaptedAnalysisResult(result, summary);
    }

    private QueryResult standardResult(
            AnalysisExecutionResult execution,
            String summary,
            QueryPlan plan) {
        List<NormalizedDataRow> normalized = normalizedRows(execution);
        if (normalized.isEmpty()) {
            return new QueryResult(summary, List.of(), List.of());
        }
        List<ResultColumn> columns = new ArrayList<>();
        if (normalized.get(0).dimensions().isEmpty()) {
            columns.add(new ResultColumn("period", "时间范围", false));
        }
        normalized.get(0).dimensions().keySet()
                .forEach(key -> columns.add(new ResultColumn(key, key, false)));
        normalized.get(0).metrics().keySet()
                .forEach(key -> columns.add(new ResultColumn(key, key, true)));
        List<Map<String, String>> rows = normalized.stream()
                .map(row -> displayRow(row, plan))
                .toList();
        return new QueryResult(summary, columns, rows);
    }

    private QueryResult comparisonResult(
            AnalysisExecutionResult execution,
            String summary,
            QueryPlan plan) {
        if (!(execution.calculationResult() instanceof ComparisonResult comparison)) {
            return new QueryResult(summary, List.of(), List.of());
        }
        List<ResultColumn> columns = List.of(
                new ResultColumn("comparisonSubject", "对比对象", false),
                new ResultColumn(plan.metricCode(), plan.metricCode(), true));
        return new QueryResult(
                summary,
                columns,
                List.of(
                        Map.of(
                                "comparisonSubject", comparison.subjectALabel(),
                                plan.metricCode(), number(comparison.subjectAValue())),
                        Map.of(
                                "comparisonSubject", comparison.subjectBLabel(),
                                plan.metricCode(), number(comparison.subjectBValue()))));
    }

    private QueryResult trendResult(
            AnalysisExecutionResult execution,
            String summary,
            QueryPlan plan) {
        if (!(execution.calculationResult() instanceof TrendSummary trend)) {
            return new QueryResult(summary, List.of(), List.of());
        }
        List<Map<String, String>> rows = trend.points().stream()
                .map(point -> trendRow(point, plan.metricCode()))
                .toList();
        return new QueryResult(
                summary,
                List.of(
                        new ResultColumn("period", "时间", false),
                        new ResultColumn(plan.metricCode(), plan.metricCode(), true)),
                rows);
    }

    private QueryResult rankingResult(
            AnalysisExecutionResult execution,
            String summary,
            QueryPlan plan) {
        if (!(execution.calculationResult() instanceof RankingResult ranking)) {
            return new QueryResult(summary, List.of(), List.of());
        }
        List<ResultColumn> columns = new ArrayList<>();
        columns.add(new ResultColumn("rank", "排名", true));
        plan.dimensionCodes().forEach(
                dimension -> columns.add(new ResultColumn(dimension, dimension, false)));
        columns.add(new ResultColumn(plan.metricCode(), plan.metricCode(), true));
        return new QueryResult(
                summary,
                columns,
                ranking.items().stream()
                        .map(item -> rankingRow(item, plan.metricCode()))
                        .toList());
    }

    private List<NormalizedDataRow> normalizedRows(AnalysisExecutionResult execution) {
        return execution.calculationResult() instanceof List<?> rows
                ? rows.stream()
                        .filter(NormalizedDataRow.class::isInstance)
                        .map(NormalizedDataRow.class::cast)
                        .toList()
                : List.of();
    }

    private Map<String, String> displayRow(NormalizedDataRow row, QueryPlan plan) {
        Map<String, String> result = new LinkedHashMap<>();
        if (row.dimensions().isEmpty()) {
            String period = plan.filters().stream()
                    .filter(filter -> filter.field().startsWith("trade"))
                    .findFirst()
                    .map(filter -> String.join(" ~ ", filter.values()))
                    .orElse("");
            result.put("period", period);
        }
        row.dimensions().forEach(
                (key, value) -> result.put(key, value == null ? "" : value.toString()));
        row.metrics().forEach((key, value) -> result.put(key, number(value)));
        return result;
    }

    private Map<String, String> trendRow(TrendPoint point, String metricCode) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("period", point.periodLabel());
        row.put(metricCode, number(point.value()));
        return row;
    }

    private Map<String, String> rankingRow(RankingItem item, String metricCode) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("rank", String.valueOf(item.rank()));
        item.dimensionValues().forEach(
                (key, value) -> row.put(key, value == null ? "" : value.toString()));
        row.put(metricCode, number(item.metricValue()));
        return row;
    }

    private String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    public record AdaptedAnalysisResult(QueryResult result, String reply) {
    }
}
