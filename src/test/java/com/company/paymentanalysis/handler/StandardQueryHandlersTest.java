package com.company.paymentanalysis.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardQueryHandlersTest {

    private final QueryExecutionService executionService =
            mock(QueryExecutionService.class);
    private final SingleQueryHandler singleHandler =
            new SingleQueryHandler(executionService);
    private final GroupQueryHandler groupHandler =
            new GroupQueryHandler(executionService);
    private final AnalysisContext context =
            new AnalysisContext(LocalDate.of(2026, 7, 31), "", List.of(), List.of());

    @Test
    void singleHandlerDelegatesOnlySingleQuery() {
        QueryPlan plan = plan(IntentType.SINGLE_QUERY);
        AnalysisExecutionResult expected = result(IntentType.SINGLE_QUERY);
        when(executionService.execute(plan)).thenReturn(expected);

        assertThat(singleHandler.support()).isEqualTo(IntentType.SINGLE_QUERY);
        assertThat(singleHandler.execute(plan, context)).isSameAs(expected);
        verify(executionService).execute(plan);
        assertThatThrownBy(() ->
                        singleHandler.execute(plan(IntentType.GROUP_QUERY), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SINGLE_QUERY");
    }

    @Test
    void groupHandlerDelegatesOnlyGroupQuery() {
        QueryPlan plan = plan(IntentType.GROUP_QUERY);
        AnalysisExecutionResult expected = result(IntentType.GROUP_QUERY);
        when(executionService.execute(plan)).thenReturn(expected);

        assertThat(groupHandler.support()).isEqualTo(IntentType.GROUP_QUERY);
        assertThat(groupHandler.execute(plan, context)).isSameAs(expected);
        verify(executionService).execute(plan);
        assertThatThrownBy(() ->
                        groupHandler.execute(plan(IntentType.SINGLE_QUERY), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GROUP_QUERY");
    }

    @Test
    void routerSelectsEachHandlerWithoutConditionBranches() {
        IntentHandlerRouter router =
                new IntentHandlerRouter(
                        List.of(singleHandler, groupHandler),
                        org.mockito.Mockito.mock(
                                com.company.paymentanalysis.execution.AnalysisExecutionAuditLogger.class));
        QueryPlan singlePlan = plan(IntentType.SINGLE_QUERY);
        QueryPlan groupPlan = plan(IntentType.GROUP_QUERY);
        when(executionService.execute(singlePlan))
                .thenReturn(result(IntentType.SINGLE_QUERY));
        when(executionService.execute(groupPlan))
                .thenReturn(result(IntentType.GROUP_QUERY));

        assertThat(router.route(singlePlan, context).intent())
                .isEqualTo(IntentType.SINGLE_QUERY);
        assertThat(router.route(groupPlan, context).intent())
                .isEqualTo(IntentType.GROUP_QUERY);
    }

    private QueryPlan plan(IntentType intent) {
        return new QueryPlan(
                intent,
                1.0,
                "rmbAmount",
                intent == IntentType.GROUP_QUERY ? List.of("region") : List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                true,
                false,
                List.of(),
                "");
    }

    private AnalysisExecutionResult result(IntentType intent) {
        return new AnalysisExecutionResult(
                "trace",
                "plan",
                intent,
                ExecutionStatus.SUCCESS,
                List.of(),
                null,
                null,
                List.of(),
                "");
    }
}
