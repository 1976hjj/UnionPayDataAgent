package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.MetadataCandidate;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope;
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

    @Test
    void injectsTypedRetrievalCandidatesInsteadOfTheWholeCatalog() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metrics\":[\"人民币总金额\"],\"groups\":[\"卡品牌\"],\"filters\":[],\"sorts\":[],\"clears\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_rmb_amt_m"],"dimensionIds":["brand"],
                         "dimensionFilters":[],"sorts":[],"explanation":"按卡品牌查看人民币总金额"}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(new MetadataCandidate(Scope.METRIC, "trans_rmb_amt_m", "人民币总金额", "", "交易金额", 1, "mock")),
                        List.of(new MetadataCandidate(Scope.DIMENSION, "brand", "卡品牌", "", "卡属性", 1, "mock")),
                        List.of(), true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };

        interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "按卡品牌看人民币总金额", QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<OpenAiCompatibleLlmClient.ChatMessage>> messagesCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeWithMessage(messagesCaptor.capture(), anyString(), eq("company-model"));
        String mappingSystemPrompt = messagesCaptor.getAllValues().get(1).get(0).content();
        assertThat(mappingSystemPrompt).contains("metricCandidates", "trans_rmb_amt_m", "brand");
        assertThat(mappingSystemPrompt).doesNotContain("acpt_cnt_m");
    }

    @Test
    void serverResolvedYesterdayOverridesAnIncorrectModelTimeField() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"metrics":["原币承兑金额"],"groups":[],"filters":[],"sorts":[],"clears":[]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["acpt_trans_amt_m"],"dimensionIds":[],
                         "dimensionFilters":[{"dimensionId":"sett_dt_Year2","operator":"EQUALS","values":["2026-08-04"]}],
                         "sorts":[],"explanation":"昨天的原币承兑金额"}
                        """, List.of()));

        var result = interpreter(llm).interpret(
                new ChatRequest("user", "session", "昨天原币承兑金额", QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().dimensionFilters()).containsExactly(
                new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                        "sett_dt_Day2", "EQUALS", List.of("2026-08-03")));
    }

    @Test
    void serverResolvedExplicitMonthsOverrideAnInventedModelTimeField() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"metrics":["承兑笔数"],"groups":["卡性质"],"filters":[],"sorts":[],"clears":[]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["acpt_cnt_m"],"dimensionIds":["card_attr_def"],
                         "dimensionFilters":[{"dimensionId":"trans_month","operator":"IN","values":["2026-03","2026-04"]}],
                         "sorts":[],"explanation":"按卡性质对比两个月的承兑笔数"}
                        """, List.of()));

        var result = interpreter(llm).interpret(
                new ChatRequest("user", "session", "2026.3对比2026.4月 度量承兑笔数 从卡性质分析",
                        QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().dimensionFilters()).containsExactly(
                new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                        "sett_dt_Month2", "IN", List.of("2026-03", "2026-04")));
    }

    @Test
    void rejectsAValueWhoseDatePrecisionDoesNotMatchTheTimeField() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metrics\":[],\"groups\":[],\"filters\":[],\"sorts\":[],\"clears\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_rmb_amt_m"],"dimensionIds":[],
                         "dimensionFilters":[{"dimensionId":"sett_dt_Year2","operator":"EQUALS","values":["2026-08-04"]}],
                         "sorts":[],"explanation":"错误日期格式"}
                        """, List.of()));

        assertThatThrownBy(() -> interpreter(llm).interpret(
                new ChatRequest("user", "session", "测试", QueryContext.empty(), "company-model", false),
                QueryContext.empty()))
                .isInstanceOf(ChatQueryInterpreter.QueryInterpretationException.class)
                .hasMessageContaining("年字段只接受 yyyy 格式");
    }

    private ChatQueryInterpreter interpreter(OpenAiCompatibleLlmClient llm) {
        return interpreter(llm, MetadataRetrievalTool.noOp());
    }

    private ChatQueryInterpreter interpreter(OpenAiCompatibleLlmClient llm, MetadataRetrievalTool retrieval) {
        return new ChatQueryInterpreter(
                llm,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC), retrieval);
    }
}
