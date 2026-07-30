package com.company.paymentanalysis.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.analysis.FilterCondition;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.DefaultAnalysisCalculationEngine;
import com.company.paymentanalysis.calculation.RankingResult;
import com.company.paymentanalysis.calculation.SortDirection;
import com.company.paymentanalysis.config.AnalysisProperties;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RankQueryHandlerTest {

    @Test
    void calculatesTopNInJavaWithoutTrustingSmartBiOrder() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any())).thenReturn(execution(List.of(
                row(0, "华东", "10"),
                row(1, "华南", "30"),
                row(2, "华北", "20"))));
        RankQueryHandler handler =
                new RankQueryHandler(
                        service,
                        new DefaultAnalysisCalculationEngine(),
                        properties());

        AnalysisExecutionResult result = handler.execute(plan(List.of("DESC"), 2), null);
        RankingResult ranking = (RankingResult) result.calculationResult();

        assertThat(ranking.direction()).isEqualTo(SortDirection.DESC);
        assertThat(ranking.items())
                .extracting(item -> item.dimensionValues().get("acquiringRegion"))
                .containsExactly("华南", "华北");
    }

    @Test
    void calculatesBottomNAndPropagatesQueryFailure() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any())).thenReturn(execution(List.of(
                row(0, "英国", "300"),
                row(1, "法国", "100"),
                row(2, "德国", "200"))));
        RankQueryHandler handler =
                new RankQueryHandler(
                        service,
                        new DefaultAnalysisCalculationEngine(),
                        properties());

        RankingResult ranking = (RankingResult)
                handler.execute(plan(List.of("LOWEST", "ASC"), 1), null)
                        .calculationResult();

        assertThat(ranking.items()).singleElement().satisfies(item ->
                assertThat(item.dimensionValues()).containsEntry("acquiringRegion", "法国"));

        when(service.execute(any())).thenReturn(new AnalysisExecutionResult(
                "trace", "plan", IntentType.GROUP_QUERY, ExecutionStatus.QUERY_FAILED,
                List.of(), null, null, List.of("failed"), ""));
        AnalysisExecutionResult failed =
                handler.execute(plan(List.of("DESC"), 5), null);
        assertThat(failed.status()).isEqualTo(ExecutionStatus.QUERY_FAILED);
        assertThat(failed.calculationResult()).isNull();
    }

    private QueryPlan plan(List<String> requested, int topN) {
        return new QueryPlan(
                IntentType.RANK_QUERY,
                1,
                "rmbAmount",
                List.of("acquiringRegion"),
                List.of(new FilterCondition(
                        "tradeDate", "BETWEEN", List.of("2026-06-01", "2026-06-30"))),
                List.of(),
                requested,
                topN,
                true,
                false,
                List.of(),
                "");
    }

    private AnalysisProperties properties() {
        return new AnalysisProperties(null, null, null, null);
    }

    private AnalysisExecutionResult execution(List<NormalizedDataRow> rows) {
        return new AnalysisExecutionResult(
                "trace", "plan", IntentType.GROUP_QUERY, ExecutionStatus.SUCCESS,
                List.of(), "raw", rows, List.of(), "");
    }

    private NormalizedDataRow row(int index, String region, String value) {
        return new NormalizedDataRow(
                Map.of("acquiringRegion", region),
                Map.of("rmbAmount", new BigDecimal(value)),
                index);
    }
}
