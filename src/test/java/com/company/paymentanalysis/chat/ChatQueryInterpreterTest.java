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
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.MetadataCandidate;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChatQueryInterpreterTest {

    @Test
    void acceptsACompleteProductionQueryStateFromAnOpenAiCompatibleModel() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metricTerms\":[\"人民币总金额\"],\"groupTerms\":[\"月\"],\"filterTerms\":[],\"sortTerms\":[],\"unmappedTerms\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {
                          "metricIds":["trans_rmb_amt_m"],
                          "dimensionIds":["sett_dt_Month2"],
                          "dimensionFilters":[{"dimensionId":"acq_mkt_ch","operator":"IN","values":["上海","北京"]}],
                          "sorts":[{"fieldId":"trans_rmb_amt_m","direction":"DESC"}],
                          "unresolvedItems":[]
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
                        "{\"metricTerms\":[\"人民币总金额\"],\"groupTerms\":[\"卡品牌\"],\"filterTerms\":[],\"sortTerms\":[],\"unmappedTerms\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_rmb_amt_m"],"dimensionIds":["brand"],
                         "dimensionFilters":[],"sorts":[],"unresolvedItems":[]}
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
        assertThat(mappingSystemPrompt).contains("完整允许度量字段");
    }

    @Test
    void mapsComparisonMonthsWithoutAQuerySpecificRepairPass() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metricTerms\":[\"业务数据\"],\"groupTerms\":[\"月\"],\"filterTerms\":[{\"dimensionTerm\":\"月\",\"operator\":\"IN\",\"values\":[\"2025-04\",\"2025-05\"],\"context\":\"对比去年5月和4月\"}],\"sortTerms\":[],\"unmappedTerms\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":[],"dimensionIds":["sett_dt_Month2"],
                         "dimensionFilters":[{"dimensionId":"sett_dt_Month2","operator":"IN","values":["2025-04","2025-05"]}],
                         "sorts":[],"unresolvedItems":["业务数据"]}
                        """, List.of()));

        var result = interpreter(llm).interpret(
                new ChatRequest("user", "session", "对比去年5月和4月业务数据", QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().dimensionIds()).containsExactly("sett_dt_Month2");
        assertThat(result.action().dimensionFilters().get(0).values())
                .containsExactly("2025-04", "2025-05");
        verify(llm, times(2)).completeWithMessage(anyList(), anyString(), eq("company-model"));
    }

    @Test
    void firstStageReturnsOneCompleteSemanticIntentWhenPendingIntentExists() throws Exception {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metricTerms\":[\"总交易笔数\"],\"groupTerms\":[],\"filterTerms\":[{\"dimensionTerm\":\"vcc\",\"operator\":\"EQUALS\",\"values\":[\"vcc\"],\"context\":\"vcc\"}],\"sortTerms\":[],\"unmappedTerms\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_cnt_m"],"dimensionIds":[],"dimensionFilters":[],"sorts":[],
                         "unresolvedItems":["vcc"]}
                        """, List.of()));
        AtomicReference<String> retrievedIntent = new AtomicReference<>();
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                retrievedIntent.set(semanticIntent);
                return new RetrievedMetadata(
                        List.of(new MetadataCandidate(Scope.METRIC, "trans_cnt_m", "总交易笔数", "", "", 1, "mock")),
                        List.of(), List.of(), true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };
        String pending = "{\"metricTerms\":[],\"groupTerms\":[],\"filterTerms\":[{\"dimensionTerm\":\"vcc\","
                + "\"operator\":\"EQUALS\",\"values\":[\"vcc\"],\"context\":\"vcc\"}],\"sortTerms\":[],\"unmappedTerms\":[]}";

        interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "总交易笔数", QueryContext.empty(), "company-model", false, pending),
                QueryContext.empty(), pending);

        var merged = new ObjectMapper().readTree(retrievedIntent.get());
        assertThat(merged.path("metricTerms").get(0).asText()).isEqualTo("总交易笔数");
        assertThat(merged.path("filterTerms").get(0).path("dimensionTerm").asText()).isEqualTo("vcc");
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
