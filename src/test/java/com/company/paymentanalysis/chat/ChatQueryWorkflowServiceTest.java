package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.PendingResolution;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryActionResult;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.smartbi.AuthorizedSmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatQueryWorkflowServiceTest {

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
}
