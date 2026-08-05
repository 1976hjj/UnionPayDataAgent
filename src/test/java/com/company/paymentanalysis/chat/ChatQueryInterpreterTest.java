package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    void acceptsACompleteProductionQueryStateFromAnOpenAiCompatibleModel() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(new LlmResultMessage("company-model", "assistant", """
                        {
                          "metricIds":["trans_rmb_amt_m"],
                          "dimensionIds":["sett_dt_Month2"],
                          "dimensionFilters":[{"dimensionId":"acq_mkt_ch","operator":"IN","values":["上海","北京"]}],
                          "sorts":[{"fieldId":"trans_rmb_amt_m","direction":"DESC"}],
                          "explanation":"按月查看上海和北京的人民币总金额。"
                        }
                        """, List.of()));

        var result = interpreter(llm).interpret(
                new ChatRequest("user", "session", "按月看上海和北京金额", QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().metricIds())
                .containsExactly("trans_rmb_amt_m");
        assertThat(result.action().dimensionIds())
                .containsExactly("sett_dt_Month2");
        assertThat(result.action().dimensionFilters().get(0).dimensionId())
                .isEqualTo("acq_mkt_ch");
        verify(llm, times(2)).completeWithMessage(anyList(), anyString(), eq("company-model"));
    }

    private ChatQueryInterpreter interpreter(OpenAiCompatibleLlmClient llm) {
        return new ChatQueryInterpreter(
                llm,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
    }
}
