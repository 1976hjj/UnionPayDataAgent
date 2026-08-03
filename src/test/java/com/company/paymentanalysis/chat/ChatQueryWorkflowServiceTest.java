package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryInterpretationException;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatQueryWorkflowServiceTest {

    @Test
    void returnsStructuredFailureWithFailedNodeAndLlmTrace() throws Exception {
        ChatQueryInterpreter interpreter = mock(ChatQueryInterpreter.class);
        SmartBiQueryBuilder queryBuilder = mock(SmartBiQueryBuilder.class);
        SmartBiClient smartBiClient = mock(SmartBiClient.class);
        LlmResultMessage llmMessage = new LlmResultMessage(
                "GLM-4.7",
                "assistant",
                "{\"dimensionAction\":{\"operations\":[{\"action\":\"SET\"}]}}",
                List.of(
                        new ChatMessage("system", "协议"),
                        new ChatMessage("user", "查下澳门的最近3个月交易金额"),
                        new ChatMessage("assistant", "首次不合规响应"),
                        new ChatMessage("user", "协议修复指令")),
                "{\"choices\":[]}");
        when(interpreter.interpret(any(), any())).thenThrow(new QueryInterpretationException(
                "PROTOCOL_VALIDATION",
                "QueryAction 协议校验失败：dimensionAction 的 SET 必须包含完整 ids",
                llmMessage,
                new IllegalArgumentException("dimensionAction 的 SET 必须包含完整 ids")));
        when(interpreter.engineLabel(anyString())).thenReturn("GLM-4.7");
        ChatQueryWorkflowService service = new ChatQueryWorkflowService(
                interpreter, queryBuilder, smartBiClient);

        var response = service.query(new ChatRequest(
                "demo-user", "conversation", "查下澳门的最近3个月交易金额",
                QueryContext.empty(), "glm-4.7", false));

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.reply())
                .contains("大模型生成 QueryAction JSON")
                .contains("dimensionAction 的 SET 必须包含完整 ids")
                .contains("SmartBI 查询未执行");
        assertThat(response.workflowSteps()).extracting(step -> step.status())
                .containsExactly("FAILED", "SKIPPED", "SKIPPED", "SKIPPED", "SKIPPED", "COMPLETED");
        assertThat(response.workflowSteps().get(0).node()).isEqualTo("interpretQueryAction");
        assertThat(response.llmMessage()).isEqualTo(llmMessage);
        assertThat(response.queryAction()).isNull();
        assertThat(response.queryPlan()).isNull();
        assertThat(response.context()).isEqualTo(QueryContext.empty());
    }
}
