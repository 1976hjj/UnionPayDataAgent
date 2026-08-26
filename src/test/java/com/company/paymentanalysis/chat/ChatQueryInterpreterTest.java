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
import com.company.paymentanalysis.semantic.BusinessSemanticNormalizer;
import com.company.paymentanalysis.semantic.BusinessSemanticProcessor;
import com.company.paymentanalysis.semantic.BusinessSemanticProperties;
import com.company.paymentanalysis.semantic.MockBusinessSemanticRetrievalTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ChatQueryInterpreterTest {

    @Test
    void regroundsACompoundMetricWithCrossScopeValueEvidence() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"searchTerms":[{"text":"POS交易笔数","context":"查POS交易笔数和金额"}],
                         "metricTerms":["POS交易笔数","POS交易金额"],"groupTerms":[],
                         "filterTerms":[],"sortTerms":[],"unmappedTerms":[]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_cnt_m","trans_amt_m"],"dimensionIds":[],
                         "dimensionFilters":[
                           {"dimensionId":"resp_cde","operator":"EQUALS","values":["SA"]}],
                         "sorts":[],"unresolvedItems":[]}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(
                                new MetadataCandidate(
                                        Scope.METRIC, "trans_cnt_m", "总交易笔数", "", "基础指标",
                                        0.95, "POS交易笔数", "mock"),
                                new MetadataCandidate(
                                        Scope.METRIC, "trans_amt_m", "原币总金额", "", "基础指标",
                                        0.94, "POS交易金额", "mock")),
                        List.of(),
                        List.of(new MetadataCandidate(
                                Scope.VALUE, "trans_nms", "交易类型", "POS", "交易参数",
                                0.98, "POS交易笔数", "mock")),
                        true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "查POS交易笔数和金额",
                        QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().metricIds()).containsExactly("trans_cnt_m", "trans_amt_m");
        assertThat(result.action().dimensionFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.dimensionId()).isEqualTo("trans_nms");
            assertThat(filter.values()).containsExactly("POS");
        });
        assertThat(result.pendingResolutions()).isEmpty();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<OpenAiCompatibleLlmClient.ChatMessage>> messagesCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeWithMessage(messagesCaptor.capture(), anyString(), eq("company-model"));
        String mappingSystemPrompt = messagesCaptor.getAllValues().get(1).get(0).content();
        assertThat(mappingSystemPrompt)
                .contains("matchedTerm=POS交易笔数", "name=交易类型", "value=POS",
                        "槽位归类只是初步判断", "requiredFilters:", "fieldId=trans_nms");
    }

    @Test
    void keepsAValueUnresolvedWhenItMatchesMultipleFieldsAndTheLlmSelectsAllOfThem() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"searchTerms":[{"text":"韩国","context":"在韩国"}],
                         "metricTerms":["交易笔数"],"groupTerms":[],
                         "filterTerms":[{"dimensionTerm":"国家","operator":"EQUALS",
                           "values":["韩国"],"context":"在韩国"}],
                         "sortTerms":[],"unmappedTerms":[]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_cnt_m"],"dimensionIds":[],
                         "dimensionFilters":[
                           {"dimensionId":"acq_reg_ch","operator":"EQUALS","values":["韩国"]},
                           {"dimensionId":"iss_dq_ch","operator":"EQUALS","values":["韩国"]}],
                         "sorts":[],"unresolvedItems":[]}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(new MetadataCandidate(
                                Scope.METRIC, "trans_cnt_m", "总交易笔数", "", "基础指标",
                                1, "交易笔数", "mock")),
                        List.of(),
                        List.of(
                                new MetadataCandidate(
                                        Scope.VALUE, "acq_reg_ch", "收单分公司", "韩国", "地域",
                                        0.99, "韩国", "mock"),
                                new MetadataCandidate(
                                        Scope.VALUE, "iss_dq_ch", "发卡分公司", "韩国", "地域",
                                        0.98, "韩国", "mock")),
                        true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "查在韩国的交易笔数",
                        QueryContext.empty(), "company-model", false),
                QueryContext.empty());

        assertThat(result.action().metricIds()).containsExactly("trans_cnt_m");
        assertThat(result.action().dimensionFilters()).isEmpty();
        assertThat(result.unresolvedItems()).contains("韩国");
        assertThat(result.ambiguousResolutions()).singleElement().satisfies(ambiguity -> {
            assertThat(ambiguity.originalTerm()).isEqualTo("韩国");
            assertThat(ambiguity.candidates()).extracting(candidate -> candidate.fieldName())
                    .containsExactly("收单分公司", "发卡分公司");
        });

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<OpenAiCompatibleLlmClient.ChatMessage>> messagesCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeWithMessage(messagesCaptor.capture(), anyString(), eq("company-model"));
        assertThat(messagesCaptor.getAllValues().get(1).get(0).content())
                .contains("ambiguousFilters:", "fieldId=acq_reg_ch", "fieldId=iss_dq_ch");
    }

    @Test
    void appliesTheOriginalValueToWhicheverAmbiguousFieldTheUserNames() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"searchTerms":[
                           {"text":"东南亚","context":"东南亚"},
                           {"text":"收单分公司","context":"收单分公司"}],
                         "metricTerms":["交易笔数"],"groupTerms":["收单分公司"],
                         "filterTerms":[{"dimensionTerm":"地区","operator":"EQUALS",
                           "values":["东南亚"],"context":"东南亚"}],
                         "sortTerms":[],"unmappedTerms":["东南亚"]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_cnt_m"],"dimensionIds":["acq_reg_ch"],
                         "dimensionFilters":[],"sorts":[],"unresolvedItems":["东南亚"]}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(new MetadataCandidate(
                                Scope.METRIC, "trans_cnt_m", "总交易笔数", "", "基础指标",
                                1, "交易笔数", "mock")),
                        List.of(new MetadataCandidate(
                                Scope.DIMENSION, "acq_reg_ch", "收单分公司", "", "地区",
                                1, "收单分公司", "mock")),
                        List.of(
                                new MetadataCandidate(
                                        Scope.VALUE, "acq_reg_ch", "收单分公司", "东南亚", "地区",
                                        1, "东南亚", "mock"),
                                new MetadataCandidate(
                                        Scope.VALUE, "iss_dq_ch", "发卡分公司", "东南亚", "地区",
                                        1, "东南亚", "mock")),
                        true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };
        QueryContext current = new QueryContext(
                List.of("trans_cnt_m"), List.of(), List.of(), List.of());

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "收单分公司", current, "company-model", false),
                current, "{\"unmappedTerms\":[\"东南亚\"]}");

        assertThat(result.action().dimensionIds()).isEmpty();
        assertThat(result.action().dimensionFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.dimensionId()).isEqualTo("acq_reg_ch");
            assertThat(filter.operator()).isEqualTo("EQUALS");
            assertThat(filter.values()).containsExactly("东南亚");
        });
        assertThat(result.unresolvedItems()).doesNotContain("东南亚");
        assertThat(result.ambiguousResolutions()).isEmpty();
    }

    @Test
    void resolvesAnExplicitFieldValuePairAndClearsItsStaleMultiTurnText() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"searchTerms":[{"text":"有效标识","context":"有效标识为1"}],
                         "metricTerms":["总交易笔数"],"groupTerms":[],
                         "filterTerms":[{"dimensionTerm":"有效标识","operator":"EQUALS",
                           "values":["1"],"context":"有效标识为1"}],
                         "sortTerms":[],"unmappedTerms":["有效标识为1"]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["trans_cnt_m"],"dimensionIds":[],
                         "dimensionFilters":[],"sorts":[],"unresolvedItems":["有效标识为1"]}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(new MetadataCandidate(
                                Scope.METRIC, "trans_cnt_m", "总交易笔数", "", "基础指标",
                                1, "总交易笔数", "mock")),
                        List.of(new MetadataCandidate(
                                Scope.DIMENSION, "kpi_ind", "有效标识", "", "交易参数",
                                1, "有效标识", "mock")),
                        List.of(
                                new MetadataCandidate(
                                        Scope.VALUE, "kpi_ind", "有效标识", "1", "交易参数",
                                        1, "1", "mock"),
                                new MetadataCandidate(
                                        Scope.VALUE, "mm_sh_sign", "免验密码标识", "1", "交易参数",
                                        1, "1", "mock"),
                                new MetadataCandidate(
                                        Scope.VALUE, "proc_ind", "处理标记", "1", "交易参数",
                                        1, "1", "mock")),
                        true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };
        QueryContext current = new QueryContext(
                List.of("trans_cnt_m"), List.of(), List.of(), List.of());

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "有效标识", current, "company-model", false),
                current,
                "{\"filterTerms\":[{\"dimensionTerm\":\"有效标识\","
                        + "\"operator\":\"EQUALS\",\"values\":[\"1\"],"
                        + "\"context\":\"有效标识为1\"}],"
                        + "\"unmappedTerms\":[\"有效标识为1\"]}");

        assertThat(result.action().dimensionFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.dimensionId()).isEqualTo("kpi_ind");
            assertThat(filter.operator()).isEqualTo("EQUALS");
            assertThat(filter.values()).containsExactly("1");
        });
        assertThat(result.unresolvedItems()).isEmpty();
        assertThat(result.ambiguousResolutions()).isEmpty();
    }

    @Test
    void enforcesMockRetrievedBusinessRulesAfterTheMappingLlm() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("glm-5.2"))).thenReturn(
                new LlmResultMessage("glm-5.2", "assistant", """
                        {"searchTerms":[
                           {"text":"VCC","context":"VCC（虚拟商务卡）"},
                           {"text":"虚拟商务卡","context":"VCC（虚拟商务卡）"},
                           {"text":"总体业务情况","context":"总体业务情况"}],
                         "metricTerms":["总体业务情况"],"groupTerms":[],
                         "filterTerms":[
                           {"dimensionTerm":"VCC（虚拟商务卡）","operator":"EQUALS","values":["VCC（虚拟商务卡）"],"context":"VCC（虚拟商务卡）"}],
                         "sortTerms":[],"unmappedTerms":["VCC","总体业务情况"]}
                        """, List.of()),
                new LlmResultMessage("glm-5.2", "assistant", """
                        {"metricIds":[],"dimensionIds":[],
                         "dimensionFilters":[{"dimensionId":"sett_dt_Day2","operator":"BETWEEN","values":["2026-01-01","2026-08-23"]}],
                         "sorts":[],"unresolvedItems":["VCC","总体业务情况"]}
                        """, List.of()));
        MockBusinessSemanticRetrievalTool retrieval = new MockBusinessSemanticRetrievalTool(
                new ObjectMapper(),
                new BusinessSemanticProperties(
                        true, "data/ragflow/business-semantic-rules.draft.jsonl", 0.90),
                new DefaultResourceLoader());
        BusinessSemanticNormalizer normalizer = new BusinessSemanticNormalizer();
        BusinessSemanticProcessor processor = (message, intent) ->
                normalizer.normalize(intent, retrieval.retrieve(message, intent.retrievalTerms()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC),
                MetadataRetrievalTool.noOp(), processor);

        var result = interpreter.interpret(
                new ChatRequest("user", "session", "今年至今VCC（虚拟商务卡）总体业务情况",
                        QueryContext.empty(), "glm-5.2", false),
                QueryContext.empty());

        assertThat(result.action().metricIds())
                .containsExactly("trans_cnt_m", "acpt_cnt_m", "acpt_trans_rmb_amt_m");
        assertThat(result.action().dimensionFilters()).satisfiesExactly(
                filter -> assertThat(filter.dimensionId()).isEqualTo("sett_dt_Day2"),
                filter -> {
                    assertThat(filter.dimensionId()).isEqualTo("bi_tag");
                    assertThat(filter.values()).containsExactly("10", "20");
                },
                filter -> {
                    assertThat(filter.dimensionId()).isEqualTo("iss_ins_cde");
                    assertThat(filter.values()).containsExactly("22090702", "47300702");
                });
        assertThat(result.unresolvedItems()).isEmpty();
        assertThat(result.pendingResolutions()).isEmpty();
        assertThat(result.appliedSemanticRules()).extracting(rule -> rule.knowledgeId())
                .containsExactly("semantic_business_overview_metrics_001", "semantic_vcc_filter_bundle_001");
    }

    @Test
    void acceptsACompleteProductionQueryStateFromAnOpenAiCompatibleModel() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metricTerms\":[\"人民币总金额\"],\"groupTerms\":[\"月\"],"
                                + "\"filterTerms\":[{\"dimensionTerm\":\"收单市场\",\"operator\":\"IN\","
                                + "\"values\":[\"上海\",\"北京\"],\"context\":\"上海和北京\"}],"
                                + "\"sortTerms\":[],\"unmappedTerms\":[]}", List.of()),
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

    @Test
    void keepsCatalogValidMetricAsPendingConfirmationWhenRetrievalDoesNotEvidenceIt() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant",
                        "{\"metricTerms\":[\"承兑笔数\",\"承兑人民币金额\"],\"groupTerms\":[],\"filterTerms\":[],\"sortTerms\":[],\"unmappedTerms\":[]}", List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":["acpt_cnt_m","acpt_trans_rmb_amt_m"],"dimensionIds":[],
                         "dimensionFilters":[],"sorts":[],"unresolvedItems":[]}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(new MetadataCandidate(
                                Scope.METRIC, "acpt_cnt_m", "承兑笔数", "", "", 1, "mock")),
                        List.of(), List.of(), true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "查承兑笔数和承兑人民币金额", QueryContext.empty(),
                        "company-model", false),
                QueryContext.empty());

        assertThat(result.action().metricIds()).containsExactly("acpt_cnt_m", "acpt_trans_rmb_amt_m");
        assertThat(result.unresolvedItems()).isEmpty();
        assertThat(result.pendingResolutions()).singleElement().satisfies(pending -> {
            assertThat(pending.type()).isEqualTo("度量");
            assertThat(pending.originalTerm()).isEqualTo("承兑人民币金额");
            assertThat(pending.fieldId()).isEqualTo("acpt_trans_rmb_amt_m");
            assertThat(pending.fieldName()).isEqualTo("人民币承兑金额");
        });
    }

    @Test
    void keepsPreviouslyGroundedSlotsWhileOnlyRetrievingTheLatestTurn() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"searchTerms":[],"metricTerms":[],"groupTerms":["发卡市场"],
                         "filterTerms":[],"sortTerms":[],"unmappedTerms":[]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":[],"dimensionIds":["iss_sc_ch"],
                         "dimensionFilters":[],"sorts":[],"unresolvedItems":[]}
                        """, List.of()));
        AtomicReference<String> retrievalMessage = new AtomicReference<>();
        AtomicReference<String> retrievalIntent = new AtomicReference<>();
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                retrievalMessage.set(message);
                retrievalIntent.set(semanticIntent);
                return new RetrievedMetadata(
                        List.of(),
                        List.of(new MetadataCandidate(
                                Scope.DIMENSION, "iss_sc_ch", "发卡市场", "", "地域",
                                1, "发卡市场", "mock")),
                        List.of(), true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };
        QueryContext current = new QueryContext(
                List.of("trans_cnt_m", "acpt_cnt_m", "acpt_trans_rmb_amt_m"),
                List.of("sett_dt_Month2"),
                List.of(
                        new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                                "sett_dt_Year2", "EQUALS", List.of("2026")),
                        new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                                "kpi_ind", "IN", List.of("1", "0")),
                        new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                                "bi_tag", "BETWEEN", List.of("10", "20"))),
                List.of());

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "改成按发卡市场分组", current,
                        "company-model", false),
                current);

        assertThat(retrievalMessage.get()).isEqualTo("改成按发卡市场分组");
        assertThat(retrievalIntent.get()).doesNotContain("10", "20", "有效标识");
        assertThat(result.action().metricIds()).containsExactlyElementsOf(current.metricIds());
        assertThat(result.action().dimensionIds()).containsExactly("iss_sc_ch");
        assertThat(result.action().dimensionFilters()).containsExactlyElementsOf(current.dimensionFilters());
        assertThat(result.pendingResolutions()).isEmpty();
    }

    @Test
    void replacesOnlyTheFilterGroundedByTheLatestTurn() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model"))).thenReturn(
                new LlmResultMessage("company-model", "assistant", """
                        {"searchTerms":[{"text":"韩国","context":"收单市场改成韩国"}],
                         "metricTerms":[],"groupTerms":[],
                         "filterTerms":[{"dimensionTerm":"收单市场","operator":"EQUALS",
                           "values":["韩国"],"context":"收单市场改成韩国"}],
                         "sortTerms":[],"unmappedTerms":[]}
                        """, List.of()),
                new LlmResultMessage("company-model", "assistant", """
                        {"metricIds":[],"dimensionIds":[],
                         "dimensionFilters":[
                           {"dimensionId":"acq_mkt_ch","operator":"EQUALS","values":["韩国"]}],
                         "sorts":[],"unresolvedItems":[]}
                        """, List.of()));
        MetadataRetrievalTool retrieval = new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String message, String semanticIntent) {
                return new RetrievedMetadata(
                        List.of(),
                        List.of(new MetadataCandidate(
                                Scope.DIMENSION, "acq_mkt_ch", "收单市场", "", "地域",
                                1, "收单市场", "mock")),
                        List.of(new MetadataCandidate(
                                Scope.VALUE, "acq_mkt_ch", "收单市场", "韩国", "地域",
                                1, "韩国", "mock")), true);
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String message, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };
        var oldMarket = new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                "acq_mkt_ch", "EQUALS", List.of("香港"));
        var stableFlag = new com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter(
                "kpi_ind", "EQUALS", List.of("1"));
        QueryContext current = new QueryContext(
                List.of("trans_cnt_m"), List.of("sett_dt_Month2"),
                List.of(oldMarket, stableFlag), List.of());

        var result = interpreter(llm, retrieval).interpret(
                new ChatRequest("user", "session", "收单市场改成韩国", current,
                        "company-model", false),
                current);

        assertThat(result.action().metricIds()).containsExactly("trans_cnt_m");
        assertThat(result.action().dimensionIds()).containsExactly("sett_dt_Month2");
        assertThat(result.action().dimensionFilters())
                .contains(stableFlag)
                .anySatisfy(filter -> {
                    assertThat(filter.dimensionId()).isEqualTo("acq_mkt_ch");
                    assertThat(filter.values()).containsExactly("韩国");
                })
                .doesNotContain(oldMarket);
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
