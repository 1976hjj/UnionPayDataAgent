package com.company.paymentanalysis.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.AnalysisExecutionAuditLogger;
import com.company.paymentanalysis.execution.ExecutionStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntentHandlerRouterTest {

    @Test
    void routesToTheHandlerRegisteredForTheIntent() {
        AtomicReference<QueryPlan> receivedPlan = new AtomicReference<>();
        AtomicReference<AnalysisContext> receivedContext = new AtomicReference<>();
        AnalysisExecutionResult expected = result(IntentType.SINGLE_QUERY);
        IntentHandler handler = new StubHandler(IntentType.SINGLE_QUERY) {
            @Override
            public AnalysisExecutionResult execute(
                    QueryPlan queryPlan,
                    AnalysisContext context) {
                receivedPlan.set(queryPlan);
                receivedContext.set(context);
                return expected;
            }
        };
        IntentHandlerRouter router =
                new IntentHandlerRouter(List.of(handler), auditLogger());
        QueryPlan plan = plan(IntentType.SINGLE_QUERY);
        AnalysisContext context =
                new AnalysisContext(LocalDate.of(2026, 7, 31), "", List.of(), List.of());

        AnalysisExecutionResult actual = router.route(plan, context);

        assertThat(actual).isSameAs(expected);
        assertThat(receivedPlan.get()).isSameAs(plan);
        assertThat(receivedContext.get()).isSameAs(context);
        assertThat(router.supports(IntentType.SINGLE_QUERY)).isTrue();
        assertThat(router.supports(IntentType.GROUP_QUERY)).isFalse();
    }

    @Test
    void rejectsDuplicateHandlersAtConstructionTime() {
        IntentHandler first = new StubHandler(IntentType.COMPARE_QUERY);
        IntentHandler second = new StubHandler(IntentType.COMPARE_QUERY);

        assertThatThrownBy(() -> new IntentHandlerRouter(
                        List.of(first, second),
                        auditLogger()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPARE_QUERY")
                .hasMessageContaining("重复 Handler");
    }

    @Test
    void returnsExplicitExceptionForUnimplementedIntent() {
        IntentHandlerRouter router = new IntentHandlerRouter(List.of(), auditLogger());

        assertThatThrownBy(() -> router.route(plan(IntentType.TREND_QUERY), null))
                .isInstanceOf(UnsupportedIntentException.class)
                .hasMessage("暂不支持该意图：TREND_QUERY")
                .extracting("intent")
                .isEqualTo(IntentType.TREND_QUERY);
    }

    @Test
    void rejectsMissingPlanOrIntentInsteadOfGuessingHandler() {
        IntentHandlerRouter router = new IntentHandlerRouter(List.of(), auditLogger());

        assertThatThrownBy(() -> router.route(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QueryPlan 不能为空");
        assertThatThrownBy(() -> router.route(plan(null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QueryPlan.intent 不能为空");
    }

    private QueryPlan plan(IntentType intent) {
        return new QueryPlan(
                intent,
                1.0,
                "rmbAmount",
                List.of(),
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
                "trace-1",
                "plan-1",
                intent,
                ExecutionStatus.SUCCESS,
                List.of(),
                null,
                null,
                List.of(),
                "");
    }

    private AnalysisExecutionAuditLogger auditLogger() {
        return org.mockito.Mockito.mock(AnalysisExecutionAuditLogger.class);
    }

    private static class StubHandler implements IntentHandler {

        private final IntentType intent;

        private StubHandler(IntentType intent) {
            this.intent = intent;
        }

        @Override
        public IntentType support() {
            return intent;
        }

        @Override
        public AnalysisExecutionResult execute(
                QueryPlan queryPlan,
                AnalysisContext context) {
            return null;
        }
    }
}
