package com.company.paymentanalysis.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.MemberEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.attribution.AttributionQueryService.QueryExecution;
import com.company.paymentanalysis.attribution.AttributionReasoner.NextDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.PlanDecision;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributionWorkflowValidationTest {

    @Test
    void rejectsAnInventedDimensionFromTheReasoner() throws Exception {
        AttributionQueryService queries = mock(AttributionQueryService.class);
        AttributionEvidenceCalculator calculator = mock(AttributionEvidenceCalculator.class);
        AttributionReasoner reasoner = mock(AttributionReasoner.class);
        when(queries.queryOverall(any())).thenReturn(execution("overall", null));
        when(calculator.overall(any(), any())).thenReturn(overall());
        when(reasoner.plan(any(), anyList(), anyInt()))
                .thenReturn(new PlanDecision("非法计划", List.of("invented_dimension"), "测试", null));

        AttributionWorkflowService workflow = new AttributionWorkflowService(queries, calculator, reasoner);

        assertThatThrownBy(() -> workflow.analyze(request()))
                .satisfies(error -> assertThat(rootCause(error).getMessage()).contains("非法或重复的归因维度"));
    }

    @Test
    void rejectsAMemberThatDoesNotExistInTheSelectedEvidence() throws Exception {
        AttributionQueryService queries = mock(AttributionQueryService.class);
        AttributionEvidenceCalculator calculator = mock(AttributionEvidenceCalculator.class);
        AttributionReasoner reasoner = mock(AttributionReasoner.class);
        Evidence evidence = evidence();
        when(queries.queryOverall(any())).thenReturn(execution("overall", null));
        when(queries.queryDimension(any(), eq("acq_ins_ch"), anyList(), eq(1)))
                .thenReturn(execution("depth1", "acq_ins_ch"));
        when(calculator.overall(any(), any())).thenReturn(overall());
        when(calculator.evidence(any(), any(), any(), eq("acq_ins_ch"), eq(1), anyList(), any()))
                .thenReturn(evidence);
        when(reasoner.plan(any(), anyList(), anyInt()))
                .thenReturn(new PlanDecision("探索机构", List.of("acq_ins_ch"), "测试", null));
        when(reasoner.next(any(), any(), anyList(), anyList(), eq(1)))
                .thenReturn(new NextDecision(
                        true, evidence.id(), "不存在的机构", "iss_sc_ch", "继续", "测试", null));

        AttributionWorkflowService workflow = new AttributionWorkflowService(queries, calculator, reasoner);

        assertThatThrownBy(() -> workflow.analyze(request()))
                .satisfies(error -> assertThat(rootCause(error).getMessage()).contains("不存在的成员"));
    }

    private EffectiveRequest request() {
        return new EffectiveRequest(
                "trans_rmb_amt_m", "2026-07", "2026-06", List.of(), 2, 8, 5, null);
    }

    private OverallEvidence overall() {
        return new OverallEvidence(
                decimal("80"), decimal("100"), decimal("-20"), decimal("-20"), decimal("-20"), "DOWN");
    }

    private Evidence evidence() {
        MemberEvidence driver = new MemberEvidence(
                1, "收单机构A", decimal("40"), decimal("60"), decimal("-20"),
                decimal("-33.3333"), decimal("100"), "DOWN", true);
        return new Evidence(
                "evidence-1", 1, "探索机构", "acq_ins_ch", "收单机构名称",
                List.of(), List.of(driver), driver, decimal("100"), true);
    }

    private QueryExecution execution(String stage, String dimension) {
        QueryRequest request = new QueryRequest("test", List.of(), List.of("trans_rmb_amt_m"), List.of(), null);
        return new QueryExecution(
                new QueryResponse("test", List.of(), Map.of()),
                new QueryTrace(stage, dimension, request));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
