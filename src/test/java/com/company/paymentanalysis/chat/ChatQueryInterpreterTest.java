package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatQueryInterpreterTest {

    @Test
    void readsAnInterpretationWrappedInAnAnswerObject() {
        OpenAiCompatibleLlmClient llmClient = mock(OpenAiCompatibleLlmClient.class);
        when(llmClient.completeWithMessage(anyList(), anyString()))
                .thenReturn(new LlmResultMessage(
                        "glm-4-flash-250414",
                        "assistant",
                        """
                        {
                          "answer": {
                            "intent": "QUERY",
                            "periodAction": "KEEP",
                            "metricAction": {
                              "operations": [{"action": "KEEP", "ids": []}]
                            },
                            "dimensionAction": {
                              "operations": [{"action": "REMOVE", "ids": ["region"]}]
                            }
                          }
                        }
                        """,
                        List.of()));

        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llmClient,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC));
        QueryContext current = new QueryContext(
                "2026-07-01",
                "2026-07-30",
                "本月（默认）",
                List.of("transactionAmount"),
                List.of("region", "channel"));

        ChatQueryInterpreter.Interpretation parsed = interpreter.interpret(
                        new ChatRequest("test-user", "test-session", "继续查询", current),
                        current)
                .interpretation();

        assertThat(parsed.dimensionAction().operations())
                .containsExactly(new ChatQueryInterpreter.ActionOperation("REMOVE", List.of("region")));
    }
}
