package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.AnalysisCalculationEngine;
import com.company.paymentanalysis.calculation.RankingRequest;
import com.company.paymentanalysis.calculation.RankingResult;
import com.company.paymentanalysis.calculation.SortDirection;
import com.company.paymentanalysis.config.AnalysisProperties;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RankQueryHandler implements IntentHandler {

    private final QueryExecutionService executionService;
    private final AnalysisCalculationEngine calculationEngine;
    private final AnalysisProperties properties;

    public RankQueryHandler(
            QueryExecutionService executionService,
            AnalysisCalculationEngine calculationEngine,
            AnalysisProperties properties) {
        this.executionService = executionService;
        this.calculationEngine = calculationEngine;
        this.properties = properties;
    }

    @Override
    public IntentType support() {
        return IntentType.RANK_QUERY;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan plan, AnalysisContext context) {
        validate(plan);
        QueryPlan groupedQuery = new QueryPlan(
                IntentType.GROUP_QUERY,
                plan.confidence(),
                plan.metricCode(),
                plan.dimensionCodes(),
                plan.filters(),
                List.of(),
                plan.requestedCalculations(),
                null,
                true,
                false,
                List.of(),
                "");
        AnalysisExecutionResult execution = executionService.execute(groupedQuery);
        if (execution.status() != ExecutionStatus.SUCCESS) {
            return copy(execution, null);
        }
        RankingResult ranking = calculationEngine.rank(
                normalizedRows(execution),
                new RankingRequest(
                        plan.metricCode(),
                        direction(plan),
                        limit(plan),
                        properties.ranking().nullValuePolicy()));
        return new AnalysisExecutionResult(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                support(),
                ranking.actualCount() == 0
                        ? ExecutionStatus.NO_DATA
                        : ExecutionStatus.SUCCESS,
                execution.queryRecords(),
                execution.rawData(),
                ranking,
                merge(
                        merge(execution.warnings(), ranking.warnings()),
                        List.of("排名执行策略："
                                + properties.ranking().executionMode()
                                + "；最终排序由Java校验")),
                "");
    }

    private void validate(QueryPlan plan) {
        if (plan == null || plan.intent() != support()) {
            throw new IllegalArgumentException("RankQueryHandler 只处理 RANK_QUERY");
        }
        if (plan.metricCode().isBlank()) {
            throw new IllegalArgumentException("排名指标不能为空");
        }
        if (plan.dimensionCodes().isEmpty()) {
            throw new IllegalArgumentException("排名查询必须包含分组维度");
        }
        if (plan.topN() != null && plan.topN() > properties.ranking().maxTopN()) {
            throw new IllegalArgumentException(
                    "排名 TopN 不能超过 " + properties.ranking().maxTopN());
        }
    }

    private int limit(QueryPlan plan) {
        return plan.topN() == null
                ? properties.ranking().defaultTopN()
                : plan.topN();
    }

    private SortDirection direction(QueryPlan plan) {
        String requested = String.join(" ", plan.requestedCalculations())
                .toUpperCase(Locale.ROOT);
        return requested.contains("ASC")
                        || requested.contains("BOTTOM")
                        || requested.contains("LOWEST")
                        || requested.contains("最低")
                        || requested.contains("最少")
                        || requested.contains("后")
                ? SortDirection.ASC
                : SortDirection.DESC;
    }

    private List<NormalizedDataRow> normalizedRows(AnalysisExecutionResult execution) {
        return execution.calculationResult() instanceof List<?> rows
                ? rows.stream()
                        .filter(NormalizedDataRow.class::isInstance)
                        .map(NormalizedDataRow.class::cast)
                        .toList()
                : List.of();
    }

    private AnalysisExecutionResult copy(
            AnalysisExecutionResult execution,
            Object calculationResult) {
        return new AnalysisExecutionResult(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                support(),
                execution.status(),
                execution.queryRecords(),
                execution.rawData(),
                calculationResult,
                execution.warnings(),
                "");
    }

    private List<String> merge(List<String> first, List<String> second) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }
}
