package com.company.paymentanalysis.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.company.paymentanalysis.smartbi.SmartBiSqlPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentQueryPipelineTest {

    private static final LocalDate CURRENT_DATE = LocalDate.of(2026, 7, 31);

    private final AnalysisCatalog catalog = new AnalysisCatalog();
    private final QueryPlanBuilder planBuilder = new QueryPlanBuilder(catalog);
    private final QueryPlanValidator validator = new QueryPlanValidator();
    private final SmartBiQueryBuilder smartBiBuilder = new SmartBiQueryBuilder(new SmartBiProperties(
            "http://localhost", "/query", "payment_query_dataset", "", ""));

    @Test
    void recognizesSingleRmbAmountQueryForJune() {
        QueryPlan plan = pipeline(
                "查询6月人民币总金额",
                emptyContext(),
                json(
                        "SINGLE_QUERY",
                        "人民币总金额",
                        "[]",
                        juneFilter(),
                        "[]").replace("\"topN\":null", "\"topN\":0"));

        assertThat(plan.intent()).isEqualTo(IntentType.SINGLE_QUERY);
        assertThat(plan.topN()).isNull();
        assertThat(plan.metricCode()).isEqualTo("rmbAmount");
        QueryRequest request = smartBiBuilder.build(plan);
        assertThat(request.rows()).isEmpty();
        assertThat(request.columns()).containsExactly("acpt_trans_rmb_amt_m");
        assertThat(request.filters()).singleElement()
                .satisfies(filter -> {
                    assertThat(filter.name()).isEqualTo("trade_date");
                    assertThat(filter.values()).containsExactly("2026-06-01", "2026-06-30");
                });
    }

    @Test
    void recognizesJuneRmbAmountGroupedByAcquiringRegion() {
        QueryPlan plan = pipeline(
                "查询6月各收单地区人民币总金额",
                emptyContext(),
                json(
                        "GROUP_QUERY",
                        "人民币总金额",
                        "[\"收单地区\"]",
                        juneFilter(),
                        "[]"));

        assertThat(plan.intent()).isEqualTo(IntentType.GROUP_QUERY);
        assertThat(plan.dimensionCodes()).containsExactly("acquiringRegion");
        QueryRequest request = smartBiBuilder.build(plan);
        assertThat(request.rows()).containsExactly("acq_mkt_ch");
        assertThat(request.columns()).containsExactly("acpt_trans_rmb_amt_m");
    }

    @Test
    void preservesJuneAndJulyAsTwoComparisonSubjects() {
        QueryPlan plan = pipeline(
                "6月比7月少了多少金额",
                emptyContext(),
                json(
                        "COMPARE_QUERY",
                        "交易金额",
                        "[]",
                        "[]",
                        """
                        [
                          {"label":"6月","filters":[{"field":"tradeDate","operator":"BETWEEN",
                            "values":["2026-06-01","2026-06-30"]}]},
                          {"label":"7月","filters":[{"field":"tradeDate","operator":"BETWEEN",
                            "values":["2026-07-01","2026-07-31"]}]}
                        ]
                        """));

        assertThat(plan.intent()).isEqualTo(IntentType.COMPARE_QUERY);
        assertThat(plan.comparisonSubjects()).extracting(ComparisonSubject::label)
                .containsExactly("6月", "7月");
        QueryRequest request = smartBiBuilder.build(plan);
        assertThat(request.rows()).containsExactly("sett_dt_Month2");
        assertThat(request.filters()).hasSize(2);
        assertThat(request.filters().get(0).values())
                .containsExactly("2026-06-01", "2026-06-30");
        assertThat(request.filters().get(1).values())
                .containsExactly("2026-07-01", "2026-07-31");
        assertThat(request.relationNode().childNodes().get(0).relation()).isEqualTo("OR");
        assertThat(SmartBiSqlPreview.from(request))
                .contains("trade_date BETWEEN '2026-06-01' AND '2026-06-30' OR trade_date BETWEEN");
    }

    @Test
    void preservesUnitedKingdomAndFranceAsTwoComparisonSubjects() {
        AnalysisContext currentMonth = new AnalysisContext(
                CURRENT_DATE,
                "",
                List.of(),
                List.of(new FilterCondition(
                        "tradeDate", "BETWEEN", List.of("2026-07-01", "2026-07-31"))));
        QueryPlan plan = pipeline(
                "英国比法国多多少交易金额",
                currentMonth,
                json(
                        "COMPARE_QUERY",
                        "交易金额",
                        "[]",
                        "[]",
                        """
                        [
                          {"label":"英国","filters":[{"field":"收单地区","operator":"EQUALS","values":["英国"]}]},
                          {"label":"法国","filters":[{"field":"收单地区","operator":"EQUALS","values":["法国"]}]}
                        ]
                        """));

        assertThat(plan.comparisonSubjects()).extracting(ComparisonSubject::label)
                .containsExactly("英国", "法国");
        QueryRequest request = smartBiBuilder.build(plan);
        assertThat(request.rows()).containsExactly("acq_mkt_ch");
        assertThat(request.filters()).extracting(filter -> filter.values().get(0))
                .containsExactly("2026-07-01", "英国", "法国");
        assertThat(request.relationNode().childNodes()).hasSize(2);
        assertThat(request.relationNode().childNodes().get(1).relation()).isEqualTo("OR");
    }

    @Test
    void asksForTimeWhenOnlyAmountIsSpecified() {
        QueryPlan plan = pipeline(
                "查一下金额",
                emptyContext(),
                json("SINGLE_QUERY", "交易金额", "[]", "[]", "[]"));

        assertThat(plan.intent()).isEqualTo(IntentType.CLARIFICATION);
        assertThat(plan.needsDataQuery()).isFalse();
        assertThat(plan.missingSlots()).contains("time");
        assertThat(plan.clarificationQuestion()).contains("时间");
    }

    @Test
    void asksForMetricWhenOnlyRegionGroupingIsSpecified() {
        QueryPlan plan = pipeline(
                "查询各地区数据但没有指定指标",
                emptyContext(),
                json(
                        "GROUP_QUERY",
                        "",
                        "[\"收单地区\"]",
                        juneFilter(),
                        "[]"));

        assertThat(plan.intent()).isEqualTo(IntentType.CLARIFICATION);
        assertThat(plan.needsDataQuery()).isFalse();
        assertThat(plan.missingSlots()).contains("metric");
        assertThat(plan.clarificationQuestion()).contains("指标");
    }

    private QueryPlan pipeline(String question, AnalysisContext context, String llmJson) {
        OpenAiCompatibleLlmClient llmClient = mock(OpenAiCompatibleLlmClient.class);
        when(llmClient.completeWithMessage(anyList(), anyString()))
                .thenReturn(new LlmResultMessage("test-model", "assistant", llmJson, List.of(), llmJson));
        IntentRecognitionService recognitionService =
                new IntentRecognitionService(llmClient, new ObjectMapper());
        IntentRecognitionResult recognition = recognitionService.recognize(question, context).result();
        return validator.validate(planBuilder.build(recognition, context));
    }

    private AnalysisContext emptyContext() {
        return new AnalysisContext(CURRENT_DATE, "", List.of(), List.of());
    }

    private String juneFilter() {
        return """
                [{"field":"tradeDate","operator":"BETWEEN","values":["2026-06-01","2026-06-30"]}]
                """;
    }

    private String json(
            String intent,
            String metricText,
            String dimensions,
            String filters,
            String comparisonSubjects) {
        return """
                {
                  "intent":"%s",
                  "confidence":0.98,
                  "metricText":"%s",
                  "dimensionTexts":%s,
                  "filters":%s,
                  "comparisonSubjects":%s,
                  "requestedCalculations":[],
                  "topN":null,
                  "needsDataQuery":true,
                  "needsKnowledgeBase":false,
                  "missingSlots":[],
                  "clarificationQuestion":""
                }
                """.formatted(intent, metricText, dimensions, filters, comparisonSubjects);
    }
}
