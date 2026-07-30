package com.company.paymentanalysis.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.analysis.ComparisonSubject;
import com.company.paymentanalysis.analysis.FilterCondition;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.ComparisonResult;
import com.company.paymentanalysis.calculation.DefaultAnalysisCalculationEngine;
import com.company.paymentanalysis.config.AnalysisProperties;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import com.company.paymentanalysis.strategy.comparison.GroupedComparisonQueryStrategy;
import com.company.paymentanalysis.strategy.comparison.SeparateComparisonQueryStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompareQueryHandlerTest {

    @Test
    void groupedStrategyMapsSubjectsByDimensionValueNotResponseOrder() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any())).thenReturn(execution(List.of(
                row("tradeMonth", "2026-07", "1000", 0),
                row("tradeMonth", "2026-06", "800", 1))));
        CompareQueryHandler handler = new CompareQueryHandler(
                List.of(
                        new GroupedComparisonQueryStrategy(service),
                        new SeparateComparisonQueryStrategy(service)),
                new DefaultAnalysisCalculationEngine(),
                properties());

        AnalysisExecutionResult execution = handler.execute(monthPlan(), null);
        ComparisonResult result = (ComparisonResult) execution.calculationResult();

        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.subjectAValue()).isEqualByComparingTo("800");
        assertThat(result.subjectBValue()).isEqualByComparingTo("1000");
        assertThat(result.signedDifference()).isEqualByComparingTo("-200");
        assertThat(result.displayDifference()).isEqualByComparingTo("200");
    }

    @Test
    void groupedStrategyDoesNotTreatMissingSubjectAsZero() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any())).thenReturn(execution(List.of(
                row("tradeMonth", "2026-06", "800", 0))));
        CompareQueryHandler handler = new CompareQueryHandler(
                List.of(new GroupedComparisonQueryStrategy(service)),
                new DefaultAnalysisCalculationEngine(),
                properties());

        AnalysisExecutionResult result = handler.execute(monthPlan(), null);

        assertThat(result.status()).isEqualTo(ExecutionStatus.PARTIAL_SUCCESS);
        assertThat(result.calculationResult()).isNull();
        assertThat(result.warnings()).anyMatch(value -> value.contains("7月"));
    }

    @Test
    void separateStrategyRunsBothSubjectsForDifferentFilterStructures() {
        QueryExecutionService service = mock(QueryExecutionService.class);
        when(service.execute(any()))
                .thenReturn(execution(List.of(total("1200"))))
                .thenReturn(execution(List.of(total("1000"))));
        CompareQueryHandler handler = new CompareQueryHandler(
                List.of(new SeparateComparisonQueryStrategy(service)),
                new DefaultAnalysisCalculationEngine(),
                properties());
        QueryPlan plan = new QueryPlan(
                IntentType.COMPARE_QUERY,
                1,
                "rmbAmount",
                List.of(),
                List.of(new FilterCondition(
                        "tradeDate", "BETWEEN", List.of("2026-07-01", "2026-07-31"))),
                List.of(
                        new ComparisonSubject("英国", List.of(new FilterCondition(
                                "acquiringRegion", "EQUALS", List.of("英国")))),
                        new ComparisonSubject("法国", List.of(new FilterCondition(
                                "channel", "EQUALS", List.of("法国"))))),
                List.of("A_MORE_THAN_B"),
                null,
                true,
                false,
                List.of(),
                "");

        AnalysisExecutionResult execution = handler.execute(plan, null);
        ComparisonResult result = (ComparisonResult) execution.calculationResult();

        assertThat(result.displayDifference()).isEqualByComparingTo("200");
        assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    private QueryPlan monthPlan() {
        return new QueryPlan(
                IntentType.COMPARE_QUERY,
                1,
                "rmbAmount",
                List.of(),
                List.of(),
                List.of(
                        new ComparisonSubject("6月", List.of(new FilterCondition(
                                "tradeDate", "BETWEEN", List.of("2026-06-01", "2026-06-30")))),
                        new ComparisonSubject("7月", List.of(new FilterCondition(
                                "tradeDate", "BETWEEN", List.of("2026-07-01", "2026-07-31"))))),
                List.of("A_LESS_THAN_B", "ABSOLUTE_DIFFERENCE"),
                null,
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
                "trace",
                "plan",
                IntentType.GROUP_QUERY,
                rows.isEmpty() ? ExecutionStatus.NO_DATA : ExecutionStatus.SUCCESS,
                List.of(),
                null,
                rows,
                List.of(),
                "");
    }

    private NormalizedDataRow row(
            String dimension, String member, String value, int index) {
        return new NormalizedDataRow(
                Map.of(dimension, member),
                Map.of("rmbAmount", new BigDecimal(value)),
                index);
    }

    private NormalizedDataRow total(String value) {
        return new NormalizedDataRow(
                Map.of(),
                Map.of("rmbAmount", new BigDecimal(value)),
                0);
    }
}
