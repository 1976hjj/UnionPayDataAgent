package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.AnalysisCalculationEngine;
import com.company.paymentanalysis.calculation.SortDirection;
import com.company.paymentanalysis.calculation.TimeGranularity;
import com.company.paymentanalysis.calculation.TrendRequest;
import com.company.paymentanalysis.calculation.TrendSummary;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TrendQueryHandler implements IntentHandler {

    private final QueryExecutionService executionService;
    private final AnalysisCalculationEngine calculationEngine;

    public TrendQueryHandler(
            QueryExecutionService executionService,
            AnalysisCalculationEngine calculationEngine) {
        this.executionService = executionService;
        this.calculationEngine = calculationEngine;
    }

    @Override
    public IntentType support() {
        return IntentType.TREND_QUERY;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan plan, AnalysisContext context) {
        validate(plan);
        TimeGranularity granularity = granularity(plan);
        String timeDimension = timeDimension(granularity);
        QueryPlan query = new QueryPlan(
                IntentType.GROUP_QUERY,
                plan.confidence(),
                plan.metricCode(),
                List.of(timeDimension),
                plan.filters(),
                List.of(),
                plan.requestedCalculations(),
                null,
                true,
                false,
                List.of(),
                "");
        AnalysisExecutionResult execution = executionService.execute(query);
        if (execution.status() != ExecutionStatus.SUCCESS) {
            return copy(execution, null);
        }
        TrendSummary summary = calculationEngine.summarizeTrend(
                normalizedRows(execution),
                new TrendRequest(
                        timeDimension,
                        plan.metricCode(),
                        granularity,
                        SortDirection.ASC));
        ExecutionStatus status = summary.validPointCount() == 0
                ? ExecutionStatus.NO_DATA
                : ExecutionStatus.SUCCESS;
        return new AnalysisExecutionResult(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                support(),
                status,
                execution.queryRecords(),
                execution.rawData(),
                summary,
                merge(execution.warnings(), summary.warnings()),
                "");
    }

    private void validate(QueryPlan plan) {
        if (plan == null || plan.intent() != support()) {
            throw new IllegalArgumentException("TrendQueryHandler 只处理 TREND_QUERY");
        }
        if (plan.metricCode().isBlank()) {
            throw new IllegalArgumentException("趋势查询指标不能为空");
        }
    }

    private TimeGranularity granularity(QueryPlan plan) {
        if (plan.dimensionCodes().contains("tradeDate")) {
            return TimeGranularity.DAY;
        }
        if (plan.dimensionCodes().contains("tradeYear")) {
            return TimeGranularity.YEAR;
        }
        String requested = String.join(" ", plan.requestedCalculations())
                .toUpperCase(Locale.ROOT);
        if (requested.contains("DAY")) {
            return TimeGranularity.DAY;
        }
        if (requested.contains("WEEK")) {
            return TimeGranularity.WEEK;
        }
        if (requested.contains("QUARTER")) {
            return TimeGranularity.QUARTER;
        }
        if (requested.contains("YEAR")) {
            return TimeGranularity.YEAR;
        }
        return TimeGranularity.MONTH;
    }

    private String timeDimension(TimeGranularity granularity) {
        return switch (granularity) {
            case DAY, WEEK -> "tradeDate";
            case MONTH, QUARTER -> "tradeMonth";
            case YEAR -> "tradeYear";
        };
    }

    @SuppressWarnings("unchecked")
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
