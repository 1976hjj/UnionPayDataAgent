package com.company.paymentanalysis.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.analysis.FilterCondition;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.DefaultAnalysisCalculationEngine;
import com.company.paymentanalysis.calculation.TrendDirection;
import com.company.paymentanalysis.calculation.TrendSummary;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrendQueryHandlerTest {

    @Test
    void queriesByMonthAndCalculatesTrendInJava() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any())).thenReturn(new AnalysisExecutionResult(
                "trace", "plan", IntentType.GROUP_QUERY, ExecutionStatus.SUCCESS,
                List.of(), "raw",
                List.of(
                        row(0, "2026-07", "30"),
                        row(1, "2026-05", "10"),
                        row(2, "2026-06", "20")),
                List.of(), ""));
        TrendQueryHandler handler =
                new TrendQueryHandler(service, new DefaultAnalysisCalculationEngine());

        AnalysisExecutionResult result = handler.execute(plan(), null);
        TrendSummary summary = (TrendSummary) result.calculationResult();

        assertThat(result.intent()).isEqualTo(IntentType.TREND_QUERY);
        assertThat(summary.points()).extracting(point -> point.periodLabel())
                .containsExactly("2026-05", "2026-06", "2026-07");
        assertThat(summary.totalDifference()).isEqualByComparingTo("20");
        assertThat(summary.direction()).isEqualTo(TrendDirection.UP);
    }

    @Test
    void propagatesQueryFailureWithoutGeneratingTrendConclusion() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any())).thenReturn(new AnalysisExecutionResult(
                "trace", "plan", IntentType.GROUP_QUERY, ExecutionStatus.QUERY_FAILED,
                List.of(), null, null, List.of("connection refused"), ""));
        TrendQueryHandler handler =
                new TrendQueryHandler(service, new DefaultAnalysisCalculationEngine());

        AnalysisExecutionResult result = handler.execute(plan(), null);

        assertThat(result.status()).isEqualTo(ExecutionStatus.QUERY_FAILED);
        assertThat(result.calculationResult()).isNull();
    }

    private QueryPlan plan() {
        return new QueryPlan(
                IntentType.TREND_QUERY,
                1,
                "rmbAmount",
                List.of("tradeMonth"),
                List.of(new FilterCondition(
                        "tradeDate", "BETWEEN", List.of("2026-05-01", "2026-07-31"))),
                List.of(),
                List.of("MONTH"),
                null,
                true,
                false,
                List.of(),
                "");
    }

    private NormalizedDataRow row(int index, String month, String amount) {
        return new NormalizedDataRow(
                Map.of("tradeMonth", month),
                Map.of("rmbAmount", new BigDecimal(amount)),
                index);
    }
}
