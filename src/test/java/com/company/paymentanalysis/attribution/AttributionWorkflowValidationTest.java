package com.company.paymentanalysis.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.attribution.AttributionModels.BranchAction;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.MemberEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.attribution.AttributionQueryService.QueryExecution;
import com.company.paymentanalysis.attribution.AttributionReasoner.PlanDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.ReflectionDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.ReportDecision;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributionWorkflowValidationTest {

    private static final AttributionPolicyProperties POLICY = new AttributionPolicyProperties(
            3, 2, 3, 0, new BigDecimal("10"), 1);

    @Test
    void rejectsAnInventedDimensionFromThePlanner() throws Exception {
        AttributionQueryService queries = mock(AttributionQueryService.class);
        AttributionEvidenceCalculator calculator = mock(AttributionEvidenceCalculator.class);
        AttributionReasoner reasoner = mock(AttributionReasoner.class);
        when(queries.queryOverall(any())).thenReturn(execution("overall", null));
        when(calculator.overall(any(), any())).thenReturn(overall());
        when(reasoner.plan(any(), anyList(), anyInt()))
                .thenReturn(new PlanDecision("非法计划", List.of("invented_dimension"), "测试", null));

        AttributionWorkflowService workflow = new AttributionWorkflowService(queries, calculator, reasoner, POLICY);

        assertThatThrownBy(() -> workflow.analyze(request()))
                .satisfies(error -> assertThat(rootCause(error).getMessage()).contains("非法或重复的归因维度"));
    }

    @Test
    void rejectsInvalidLlmBranchWithoutExecutingAnotherQuery() throws Exception {
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
        when(reasoner.reflect(any(), any(), anyList(), anyList(), anyList(), anyInt(), anyInt()))
                .thenReturn(new ReflectionDecision("尝试无效成员", List.of(new BranchAction(
                        "EXPAND", "MAIN", evidence.id(), "不存在的机构", "iss_sc_ch", "HIGH", "测试", "测试")), null));
        when(reasoner.report(any(), any(), anyList(), anyList(), anyList(), any()))
                .thenReturn(new ReportDecision(new AttributionModels.AttributionReport("测试", List.of(), List.of()), null));

        AttributionWorkflowService workflow = new AttributionWorkflowService(queries, calculator, reasoner, POLICY);
        var response = workflow.analyze(request());

        assertThat(response.queryCount()).isEqualTo(2);
        assertThat(response.stop().code()).isEqualTo("NO_APPROVED_BRANCH");
        assertThat(response.branches()).isEmpty();
    }

    @Test
    void supportsAThirdLevelBranchAfterUpdatingItsParentStatus() throws Exception {
        AttributionQueryService queries = mock(AttributionQueryService.class);
        AttributionEvidenceCalculator calculator = mock(AttributionEvidenceCalculator.class);
        AttributionReasoner reasoner = mock(AttributionReasoner.class);
        Evidence first = evidence("evidence-1", "acq_ins_ch", 1, "收单机构A");
        Evidence second = evidence("evidence-2", "iss_sc_ch", 2, "英国");
        Evidence third = evidence("evidence-3", "acq_mkt_ch", 3, "欧洲市场");
        when(queries.queryOverall(any())).thenReturn(execution("overall", null));
        when(queries.queryDimension(any(), eq("acq_ins_ch"), anyList(), eq(1))).thenReturn(execution("depth1", "acq_ins_ch"));
        when(queries.queryDimension(any(), eq("iss_sc_ch"), anyList(), eq(2))).thenReturn(execution("depth2", "iss_sc_ch"));
        when(queries.queryDimension(any(), eq("acq_mkt_ch"), anyList(), eq(3))).thenReturn(execution("depth3", "acq_mkt_ch"));
        when(calculator.overall(any(), any())).thenReturn(overall());
        when(calculator.evidence(any(), any(), any(), eq("acq_ins_ch"), eq(1), anyList(), any())).thenReturn(first);
        when(calculator.evidence(any(), any(), any(), eq("iss_sc_ch"), eq(2), anyList(), any())).thenReturn(second);
        when(calculator.evidence(any(), any(), any(), eq("acq_mkt_ch"), eq(3), anyList(), any())).thenReturn(third);
        when(reasoner.plan(any(), anyList(), anyInt())).thenReturn(new PlanDecision("逐层验证", List.of("acq_ins_ch"), "测试", null));
        when(reasoner.reflect(any(), any(), anyList(), anyList(), anyList(), anyInt(), anyInt())).thenReturn(
                new ReflectionDecision("扩展第一条分支", List.of(new BranchAction("EXPAND", "MAIN", first.id(), "收单机构A", "iss_sc_ch", "HIGH", "测试", "测试")), null),
                new ReflectionDecision("继续扩展已分析分支", List.of(new BranchAction("EXPAND", "MAIN", second.id(), "英国", "acq_mkt_ch", "HIGH", "测试", "测试")), null));
        when(reasoner.report(any(), any(), anyList(), anyList(), anyList(), any()))
                .thenReturn(new ReportDecision(new AttributionModels.AttributionReport("测试", List.of(), List.of()), null));

        AttributionWorkflowService workflow = new AttributionWorkflowService(queries, calculator, reasoner, POLICY);
        var response = workflow.analyze(deepRequest());

        assertThat(response.stop().code()).isEqualTo("MAX_DEPTH");
        assertThat(response.queryCount()).isEqualTo(4);
        assertThat(response.branches()).hasSize(2);
        assertThat(response.branches().get(0).status()).isEqualTo("EXPANDED");
        assertThat(response.branches().get(1).status()).isEqualTo("ANALYZED");
    }

    private EffectiveRequest request() {
        return new EffectiveRequest("trans_rmb_amt_m", "2026-07", "2026-06", List.of(), 2, 8, 5, 2, null);
    }

    private EffectiveRequest deepRequest() {
        return new EffectiveRequest("trans_rmb_amt_m", "2026-07", "2026-06", List.of(), 3, 8, 5, 2, null);
    }

    private OverallEvidence overall() {
        return new OverallEvidence(decimal("80"), decimal("100"), decimal("-20"), decimal("-20"), decimal("-20"), "DOWN");
    }

    private Evidence evidence() {
        MemberEvidence driver = new MemberEvidence(1, "收单机构A", decimal("40"), decimal("60"), decimal("-20"),
                decimal("-33.3333"), decimal("100"), "DOWN", true);
        return new Evidence("evidence-1", "root", 1, "探索机构", "acq_ins_ch", "收单机构名称",
                List.of(), List.of(driver), driver, decimal("100"), true);
    }

    private Evidence evidence(String id, String dimension, int depth, String member) {
        MemberEvidence driver = new MemberEvidence(1, member, decimal("40"), decimal("60"), decimal("-20"),
                decimal("-33.3333"), decimal("100"), "DOWN", true);
        return new Evidence(id, "root", depth, "test", dimension, dimension,
                List.of(), List.of(driver), driver, decimal("100"), true);
    }

    private QueryExecution execution(String stage, String dimension) {
        QueryRequest request = new QueryRequest("test", List.of(), List.of("trans_rmb_amt_m"), List.of(), null);
        return new QueryExecution(new QueryResponse("test", List.of(), Map.of()), new QueryTrace(stage, dimension, request));
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
