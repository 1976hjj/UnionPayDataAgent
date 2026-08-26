package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateQueryResult;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.PendingResolution;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.AmbiguousResolution;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.ResolutionCandidate;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryActionResult;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.smartbi.AuthorizedSmartBiClient;
import com.company.paymentanalysis.smartbi.AuthorizedSmartBiClient.PreparedQuery;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.company.paymentanalysis.semantic.BusinessSemanticProcessor.AppliedSemanticRule;
import com.company.paymentanalysis.semantic.QuerySemanticIntent.FilterTerm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ChatQueryWorkflowServiceTest {

    @Test
    void persistsCompletedQueryAsArtifactAndKeepsTheLegacyResult() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        AuthorizedSmartBiClient smartBiClient = mock(AuthorizedSmartBiClient.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        Artifact artifact = mock(Artifact.class);
        when(artifact.artifactId()).thenReturn("art_query_001");
        when(artifactService.createQueryResult(any(CreateQueryResult.class))).thenReturn(artifact);
        when(interpreter.engineLabel(anyString())).thenReturn("test-model");
        when(smartBiClient.prepare(anyString(), any())).thenAnswer(invocation ->
                new PreparedQuery(invocation.getArgument(0), invocation.getArgument(1)));
        when(smartBiClient.query(any(PreparedQuery.class))).thenReturn(new QueryResponse(
                "request-1", List.of(Map.of("trans_cnt_m", "123.45")), Map.of()));

        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter,
                new SmartBiQueryBuilder(new SmartBiProperties("dataset", true, "", "", "", "")),
                smartBiClient,
                new ObjectMapper(),
                ClarificationPlanner.noOp(),
                artifactService);
        QueryContext context = new QueryContext(List.of("trans_cnt_m"), List.of(), List.of(), List.of());

        var response = service.query(new ChatRequest(
                "demo-user", "session-1", "确认执行", context, "test-model", true));

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.result()).isNotNull();
        assertThat(response.result().rows()).hasSize(1);
        assertThat(response.artifactId()).isEqualTo("art_query_001");
        ArgumentCaptor<CreateQueryResult> command = ArgumentCaptor.forClass(CreateQueryResult.class);
        verify(artifactService).createQueryResult(command.capture());
        assertThat(command.getValue().ownerUserId()).isEqualTo("demo-user");
        assertThat(command.getValue().conversationId()).isEqualTo("session-1");
        assertThat(command.getValue().payload().rows().get(0).get("trans_cnt_m"))
                .isEqualTo(new java.math.BigDecimal("123.45"));
    }

    @Test
    void asksUserToChooseAmongAllExactValueFieldsWhenMultipleWereNotSelected() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        AuthorizedSmartBiClient smartBiClient = mock(AuthorizedSmartBiClient.class);
        QueryAction action = new QueryAction(List.of("trans_cnt_m"), List.of(), List.of(), List.of());
        AmbiguousResolution ambiguity = new AmbiguousResolution(
                "东南亚", "东南亚", List.of(
                        new ResolutionCandidate("acq_reg_ch", "收单分公司"),
                        new ResolutionCandidate("iss_dq_ch", "发卡分公司"),
                        new ResolutionCandidate("abd_mkt_ch3", "收单会员市场")));
        when(interpreter.interpret(any(ChatRequest.class), any(QueryContext.class))).thenReturn(
                new QueryActionResult(
                        action, "东南亚存在歧义。",
                        new LlmResultMessage("test-model", "assistant", "{}", List.of()),
                        "{}", List.of("东南亚"), List.of(), List.of(), List.of(ambiguity)));
        when(interpreter.engineLabel(anyString())).thenReturn("test-model");

        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter,
                new SmartBiQueryBuilder(new SmartBiProperties("dataset", true, "", "", "", "")),
                smartBiClient,
                new ObjectMapper(),
                ClarificationPlanner.noOp());

        var response = service.query(new ChatRequest(
                "demo-user", "session", "查东南亚交易笔数", QueryContext.empty(), "test-model", false));

        assertThat(response.status()).isEqualTo("confirming");
        assertThat(response.reply()).contains(
                "东南亚", "收单分公司", "发卡分公司", "收单会员市场", "暂未纳入", "直接回复");
        assertThat(response.pendingQueryIntent()).isEqualTo("{}");
        assertThat(response.queryPlan()).isNotNull();
        verify(smartBiClient, never()).prepare(anyString(), any());
        verify(smartBiClient, never()).query(any());
    }

    @Test
    void confirmationDoesNotPrintAppliedBusinessRulesButKeepsTheirExpandedQuery() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        AuthorizedSmartBiClient smartBiClient = mock(AuthorizedSmartBiClient.class);
        QueryAction action = new QueryAction(
                List.of("trans_cnt_m", "acpt_cnt_m", "acpt_trans_rmb_amt_m"),
                List.of(),
                List.of(
                        new DimensionFilter("bi_tag", "BETWEEN", List.of("10", "20")),
                        new DimensionFilter("iss_ins_cde", "IN", List.of("22090702", "47300702"))),
                List.of());
        List<AppliedSemanticRule> rules = List.of(
                new AppliedSemanticRule(
                        "semantic_vcc_filter_bundle_001", "VCC", "VCC组合规则",
                        "VCC：B2B产品标识处于[10,20]，且发卡机构代码属于[22090702,47300702]",
                        true, List.of(), List.of(
                                new FilterTerm("B2B产品标识", "BETWEEN", List.of("10", "20"), "规则"),
                                new FilterTerm("发卡机构代码", "IN", List.of("22090702", "47300702"), "规则"))),
                new AppliedSemanticRule(
                        "semantic_business_overview_metrics_001", "总体业务情况", "总体业务指标包",
                        "总体业务情况：总交易笔数、承兑笔数、人民币承兑金额",
                        true, List.of("总交易笔数", "承兑笔数", "人民币承兑金额"), List.of()));
        when(interpreter.interpret(any(ChatRequest.class), any(QueryContext.class))).thenReturn(
                new QueryActionResult(
                        action, "已应用业务语义规则。",
                        new LlmResultMessage("glm-5.2", "assistant", "{}", List.of()),
                        "{}", List.of(), List.of(), rules));
        when(interpreter.engineLabel(anyString())).thenReturn("GLM-5.2");
        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter,
                new SmartBiQueryBuilder(new SmartBiProperties("dataset", true, "", "", "", "")),
                smartBiClient,
                new ObjectMapper(),
                ClarificationPlanner.noOp());

        var response = service.query(new ChatRequest(
                "demo-user", "session", "VCC总体业务情况", QueryContext.empty(), "glm-5.2", false));

        assertThat(response.status()).isEqualTo("confirming");
        assertThat(response.reply()).contains(
                "总交易笔数、承兑笔数、人民币承兑金额",
                "B2B产品标识 BETWEEN 10、20",
                "发卡机构代码 IN 22090702、47300702");
        assertThat(response.reply()).doesNotContain(
                "已应用业务规则", "总体业务情况", "VCC：B2B产品标识处于");
        verify(smartBiClient, never()).query(any());
    }

    @Test
    void presentsWeakCatalogMappingForConfirmationWithoutCallingSmartBi() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        AuthorizedSmartBiClient smartBiClient = mock(AuthorizedSmartBiClient.class);
        QueryAction action = new QueryAction(
                List.of("acpt_cnt_m", "acpt_trans_rmb_amt_m"), List.of(), List.of(), List.of());
        PendingResolution pending = new PendingResolution(
                "度量", "承兑人民币金额", "acpt_trans_rmb_amt_m", "人民币承兑金额",
                "本次 RAG 未召回该字段，请确认是否按此字段查询");
        when(interpreter.interpret(any(ChatRequest.class), any(QueryContext.class))).thenReturn(
                new QueryActionResult(
                        action, "存在待用户确认的弱证据映射。",
                        new LlmResultMessage("test-model", "assistant", "{}", List.of()),
                        "{}", List.of(), List.of(pending)));
        when(interpreter.engineLabel(anyString())).thenReturn("test-model");

        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter,
                new SmartBiQueryBuilder(new SmartBiProperties("dataset", true, "", "", "", "")),
                smartBiClient,
                new ObjectMapper(),
                ClarificationPlanner.noOp());

        var response = service.query(new ChatRequest(
                "demo-user", "session", "查承兑笔数和承兑人民币金额", QueryContext.empty(),
                "test-model", false));

        assertThat(response.status()).isEqualTo("confirming");
        assertThat(response.context().metricIds()).containsExactly("acpt_cnt_m", "acpt_trans_rmb_amt_m");
        assertThat(response.reply()).contains("承兑人民币金额", "人民币承兑金额", "待确认映射");
        verify(smartBiClient, never()).prepare(anyString(), any());
        verify(smartBiClient, never()).query(any());
    }

    @Test
    void allowsConfirmationWhenOnlyAnOptionalItemIsUnresolved() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        AuthorizedSmartBiClient smartBiClient = mock(AuthorizedSmartBiClient.class);
        QueryAction action = new QueryAction(List.of("acpt_cnt_m"), List.of(), List.of(), List.of());
        when(interpreter.interpret(any(ChatRequest.class), any(QueryContext.class))).thenReturn(
                new QueryActionResult(
                        action, "未找到元数据映射：未知口径。",
                        new LlmResultMessage("test-model", "assistant", "{}", List.of()),
                        "{}", List.of("未知口径"), List.of()));
        when(interpreter.engineLabel(anyString())).thenReturn("test-model");

        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter,
                new SmartBiQueryBuilder(new SmartBiProperties("dataset", true, "", "", "", "")),
                smartBiClient,
                new ObjectMapper(),
                ClarificationPlanner.noOp());

        var response = service.query(new ChatRequest(
                "demo-user", "session", "查承兑笔数和未知口径", QueryContext.empty(), "test-model", false));

        assertThat(response.status()).isEqualTo("confirming");
        assertThat(response.context().metricIds()).containsExactly("acpt_cnt_m");
        assertThat(response.reply()).contains("未知口径", "本次不纳入查询");
        assertThat(response.queryPlan()).isNotNull();
        verify(smartBiClient, never()).prepare(anyString(), any());
    }

    @Test
    void explainsRecognizedFiltersAndTheSpecificUnresolvedTermsWhenMetricIsMissing() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        AuthorizedSmartBiClient smartBiClient = mock(AuthorizedSmartBiClient.class);
        QueryAction action = new QueryAction(
                List.of(), List.of(), List.of(
                        new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                                "sett_dt_Year2", "EQUALS", List.of("2026")),
                        new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                                "acq_mkt_ch", "EQUALS", List.of("香港"))),
                List.of());
        when(interpreter.interpret(any(ChatRequest.class), any(QueryContext.class))).thenReturn(
                new QueryActionResult(
                        action, "未找到元数据映射：交易质量、整体。",
                        new LlmResultMessage("test-model", "assistant", "{}", List.of()),
                        "{}", List.of("交易质量", "整体"), List.of()));
        when(interpreter.engineLabel(anyString())).thenReturn("test-model");

        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter,
                new SmartBiQueryBuilder(new SmartBiProperties("dataset", true, "", "", "", "")),
                smartBiClient,
                new ObjectMapper(),
                ClarificationPlanner.noOp());

        var response = service.query(new ChatRequest(
                "demo-user", "session", "今年香港市场的整体交易质量情况", QueryContext.empty(),
                "test-model", false));

        assertThat(response.status()).isEqualTo("clarifying");
        assertThat(response.reply()).contains("年 EQUALS 2026", "收单市场 EQUALS 香港", "交易质量、整体", "度量");
        assertThat(response.suggestions())
                .containsExactly("查总交易笔数", "查原币总金额", "查人民币总金额");
        verify(smartBiClient, never()).prepare(anyString(), any());
        verify(smartBiClient, never()).query(any());
    }
}
