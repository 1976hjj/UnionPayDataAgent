package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.attribution.AttributionTemplateInterpreter;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatResponse;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationState;
import com.company.paymentanalysis.chat.ChatConversationMemoryService.ConversationSnapshot;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AttributionConversationRouterServiceTest {

    @Test
    void sendsGreetingToChatWithoutMutatingTheTemplate() {
        AttributionTemplateInterpreter interpreter = mock(AttributionTemplateInterpreter.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(memory.snapshot("user", "conversation")).thenReturn(Optional.of(
                new ConversationSnapshot(QueryContext.empty(), List.of(), List.of(),
                        new TemplateConversationState("READY_TO_CONFIRM", DimensionTemplate.auto(), List.of(), List.of()))));
        when(llm.completeWithMessage(anyList(), anyString(), eq("model")))
                .thenReturn(message("{\"reply\":\"你好，我是归因分析助手。\"}"));

        TemplateChatResponse response = service(interpreter, memory, llm).respond(new TemplateChatRequest(
                "user", "conversation", "你是谁", List.of(), null, "model"));

        assertThat(response.status()).isEqualTo("CHAT");
        assertThat(response.template()).isEqualTo(DimensionTemplate.auto());
        verify(interpreter, never()).interpret(org.mockito.ArgumentMatchers.any());
        verify(memory).saveAttributionTurn(eq("user"), eq("conversation"), eq("你是谁"), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void treatsEllipticalRemovalAsTemplateEdit() {
        AttributionTemplateInterpreter interpreter = mock(AttributionTemplateInterpreter.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        DimensionTemplate template = DimensionTemplate.auto();
        when(memory.snapshot("user", "conversation")).thenReturn(Optional.of(
                new ConversationSnapshot(QueryContext.empty(), List.of(), List.of(),
                        new TemplateConversationState("READY_TO_CONFIRM", template, List.of(), List.of()))));
        when(interpreter.interpret(org.mockito.ArgumentMatchers.any())).thenReturn(new TemplateChatResponse(
                "READY_TO_CONFIRM", "已移除收单机构", template, List.of(), List.of(), null, null));

        TemplateChatResponse response = service(interpreter, memory, llm).respond(new TemplateChatRequest(
                "user", "conversation", "机构也不用管了", List.of(), null, "model"));

        assertThat(response.status()).isEqualTo("READY_TO_CONFIRM");
        verify(interpreter).interpret(org.mockito.ArgumentMatchers.argThat(request ->
                request.currentTemplate().equals(template) && request.conversationHistory().isEmpty()));
        verify(llm, never()).completeWithMessage(anyList(), anyString(), anyString());
    }

    @Test
    void neverRendersMalformedProviderJsonAsChatAnswer() {
        AttributionTemplateInterpreter interpreter = mock(AttributionTemplateInterpreter.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(memory.snapshot("user", "conversation")).thenReturn(Optional.of(
                new ConversationSnapshot(QueryContext.empty(), List.of(), List.of())));
        when(llm.completeWithMessage(anyList(), anyString(), eq("model")))
                .thenReturn(message("[\"交易渠道名称\",\"交易类型\"]"));

        TemplateChatResponse response = service(interpreter, memory, llm).respond(new TemplateChatRequest(
                "user", "conversation", "你好", List.of(), null, "model"));

        assertThat(response.status()).isEqualTo("CHAT");
        assertThat(response.reply()).doesNotContain("交易渠道名称");
    }

    private AttributionConversationRouterService service(
            AttributionTemplateInterpreter interpreter,
            ChatConversationMemoryService memory,
            OpenAiCompatibleLlmClient llm) {
        return new AttributionConversationRouterService(
                interpreter, memory,
                new ConversationRouterService(null, null, null, new ObjectMapper()),
                llm, new ObjectMapper());
    }

    private LlmResultMessage message(String content) {
        return new LlmResultMessage("model", "assistant", content, List.of());
    }
}
