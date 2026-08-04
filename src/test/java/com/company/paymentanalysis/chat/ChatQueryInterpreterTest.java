package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void acceptsProductionFieldsFromAnOpenAiCompatibleModel() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(new LlmResultMessage("company-model", "assistant", """
                        {
                          "intent":"QUERY",
                          "metricAction":{"operations":[{"action":"SET","ids":["trans_rmb_amt_m"]}]},
                          "dimensionAction":{"operations":[{"action":"SET","ids":["sett_dt_Month2"]}]},
                          "filterAction":{"operations":[{"action":"SET","dimensionId":"acq_mkt_ch","operator":"IN","values":["上海","北京"]}]},
                          "sortAction":{"action":"SET","items":[{"fieldId":"trans_rmb_amt_m","direction":"DESC"}]},
                          "explanation":"按月查看上海和北京的人民币总金额。"
                        }
                        """, List.of()));

        var result = interpreter(llm).interpret(
                new ChatRequest("user", "session", "按月看上海和北京金额", QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().metricAction().operations().get(0).ids())
                .containsExactly("trans_rmb_amt_m");
        assertThat(result.action().dimensionAction().operations().get(0).ids())
                .containsExactly("sett_dt_Month2");
        assertThat(result.action().filterAction().operations().get(0).dimensionId())
                .isEqualTo("acq_mkt_ch");
        verify(llm).completeWithMessage(anyList(), anyString(), eq("company-model"));
    }

    @Test
    void rejectsRemovedTestFieldIds() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String legacyResponse = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "sortAction":{"action":"CLEAR","items":[]},
                  "explanation":"旧字段。"
                }
                """;
        when(llm.completeWithMessage(anyList(), anyString()))
                .thenReturn(new LlmResultMessage("company-model", "assistant", legacyResponse, List.of()));

        assertThatThrownBy(() -> interpreter(llm).interpret(
                new ChatRequest("user", "session", "查询", QueryContext.empty()), QueryContext.empty()))
                .isInstanceOf(ChatQueryInterpreter.QueryInterpretationException.class)
                .hasMessageContaining("transactionAmount");
    }

    private ChatQueryInterpreter interpreter(OpenAiCompatibleLlmClient llm) {
        return new ChatQueryInterpreter(
                llm,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
    }
}
