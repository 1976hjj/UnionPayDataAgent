package com.company.paymentanalysis.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionLayer;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionSelection;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttributionTemplateInterpreterTest {

    @Test
    void resolvesRelativePeriodsEvenWhenTheModelLeavesTheDateFieldsBlank() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(message("""
                        {"analysisTerms":[],"explicitOrder":false,"requestsAutoExploration":true,
                        "requestsStopAfterTemplate":false,"metricTerm":"交易金额","currentPeriod":"",
                        "comparisonPeriod":"","filterTerms":[],"unmappedTerms":[]}
                        """))
                .thenReturn(message("""
                        {"name":"交易金额归因","mode":"AUTO","metricId":"trans_rmb_amt_m",
                        "currentPeriod":"","comparisonPeriod":"","filters":[],"levels":[],
                        "continuationMode":"AUTO","summary":"自由探索","unmappedTerms":[],"mappingIssues":[]}
                        """));

        var result = interpreterAt("2026-08-16T00:00:00Z", llm).interpret(new TemplateChatRequest(
                "user", "conversation", "当前周期，本月；对比周期，上月", List.of(), null, "company-model"));

        assertThat(result.status()).isEqualTo("READY_TO_CONFIRM");
        assertThat(result.template().currentPeriod()).isEqualTo("2026-08");
        assertThat(result.template().comparisonPeriod()).isEqualTo("2026-07");
    }

    @Test
    void extractsMetricPeriodsAndParallelDimensionsIntoACompleteTemplate() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(message("""
                        {"analysisTerms":[
                          {"term":"双标卡","level":1,"context":"第一层并行分析"},
                          {"term":"卡性质","level":1,"context":"第一层并行分析"},
                          {"term":"交易场景","level":2,"context":"第二层分析"},
                          {"term":"ExpressPay","level":3,"context":"第三层分析"}
                        ],"explicitOrder":true,"requestsAutoExploration":true,"requestsStopAfterTemplate":false,
                        "metricTerm":"人民币总金额","currentPeriod":"2026-07","comparisonPeriod":"2026-06","filterTerms":[],
                        "unmappedTerms":[]}
                        """))
                .thenReturn(message("""
                        {"name":"用户交易变化分析模板","mode":"HYBRID","metricId":"trans_rmb_amt_m",
                        "currentPeriod":"2026-07","comparisonPeriod":"2026-06","filters":[],"levels":[
                          {"level":1,"dimensions":[
                            {"dimensionId":"brand","userTerm":"双标卡","rationale":"映射为卡品牌","confidence":"HIGH"},
                            {"dimensionId":"card_attr_def","userTerm":"卡性质","rationale":"映射为卡性质名称","confidence":"HIGH"}]},
                          {"level":2,"dimensions":[
                            {"dimensionId":"trans_scen_ind","userTerm":"交易场景","rationale":"映射为IP用法","confidence":"HIGH"}]},
                          {"level":3,"dimensions":[
                            {"dimensionId":"JYJZ_NAME","userTerm":"ExpressPay","rationale":"映射为交易介质","confidence":"HIGH"}]}
                        ],"continuationMode":"AUTO","summary":"按三层分析，之后自由探索。","unmappedTerms":[],"mappingIssues":[]}
                        """));

        var result = interpreter(llm).interpret(new TemplateChatRequest(
                "user", "conversation",
                "分析2026年7月对比6月的人民币总金额。第一层同时看双标卡和卡性质，再看交易场景，最后看ExpressPay；后面自由探索。",
                List.of(), null, "company-model"));

        assertThat(result.status()).isEqualTo("READY_TO_CONFIRM");
        assertThat(result.template().metricId()).isEqualTo("trans_rmb_amt_m");
        assertThat(result.template().currentPeriod()).isEqualTo("2026-07");
        assertThat(result.template().comparisonPeriod()).isEqualTo("2026-06");
        assertThat(result.template().levels().get(0).dimensions())
                .extracting(item -> item.dimensionId()).containsExactly("brand", "card_attr_def");
        assertThat(result.template().levels()).flatExtracting(level -> level.dimensions())
                .allSatisfy(item -> assertThat(item.mappingHint()).isEmpty());
        verify(llm, times(2)).completeWithMessage(anyList(), anyString(), eq("company-model"));
    }

    @Test
    void rejectsAFieldOutsideTheAttributionCatalog() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(message("""
                        {"analysisTerms":[{"term":"未知维度","level":1,"context":"用户要求"}],"explicitOrder":true,
                        "requestsAutoExploration":false,"requestsStopAfterTemplate":true,"metricTerm":"总交易笔数",
                        "currentPeriod":"2026-07","comparisonPeriod":"2026-06","filterTerms":[],"unmappedTerms":[]}
                        """))
                .thenReturn(message("""
                        {"name":"非法模板","mode":"USER_DEFINED","metricId":"trans_cnt_m","currentPeriod":"2026-07","comparisonPeriod":"2026-06","filters":[],
                        "levels":[{"level":1,"dimensions":[{"dimensionId":"invented_field","userTerm":"未知维度","rationale":"错误映射","confidence":"HIGH"}]}],
                        "continuationMode":"STOP","summary":"非法字段","unmappedTerms":[],"mappingIssues":[]}
                        """));

        assertThatThrownBy(() -> interpreter(llm).interpret(new TemplateChatRequest(
                "user", "conversation", "按未知维度分析", List.of(), DimensionTemplate.auto(), "company-model")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分析层级模板解析失败");
    }

    @Test
    void refusesToConfirmUntilMetricAndBothPeriodsArePresent() {
        DimensionTemplate draft = new DimensionTemplate(
                "缺少任务要素", "USER_DEFINED", "", "", "", "",
                List.of(),
                List.of(new DimensionLayer(1, List.of(new DimensionSelection(
                        "brand", "卡品牌", "卡品牌", "用户指定", "", "HIGH")))),
                "STOP", "DRAFT", "仅包含分析层级");

        assertThatThrownBy(() -> interpreter(mock(OpenAiCompatibleLlmClient.class)).confirm(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须设置度量、当前周期和对比周期");
    }

    @Test
    void automaticallyPlacesTheNewerMonthInTheCurrentPeriod() {
        DimensionTemplate reversed = new DimensionTemplate(
                "日期反向模板", "USER_DEFINED", "trans_cnt_m", "总交易笔数",
                "2026-03", "2026-04", List.of(),
                List.of(new DimensionLayer(1, List.of(new DimensionSelection(
                        "kpi_ind", "有效标识", "有效标识", "用户指定", "", "HIGH")))),
                "STOP", "DRAFT", "测试日期自动调整");

        DimensionTemplate confirmed = interpreter(mock(OpenAiCompatibleLlmClient.class)).confirm(reversed);

        assertThat(confirmed.currentPeriod()).isEqualTo("2026-04");
        assertThat(confirmed.comparisonPeriod()).isEqualTo("2026-03");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void extractsAConversationFilterIntoTheTemplate() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(message("""
                        {"analysisTerms":[{"term":"卡性质","level":1,"context":"第一层分析"}],
                        "explicitOrder":true,"requestsAutoExploration":false,"requestsStopAfterTemplate":true,
                        "metricTerm":"总交易笔数","currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                        "filterTerms":[{"dimensionTerm":"卡品牌","operator":"EQUALS","values":["银联"],"context":"只看卡品牌为银联"}],
                        "unmappedTerms":[]}
                        """))
                .thenReturn(message("""
                        {"name":"银联卡交易笔数分析","mode":"USER_DEFINED","metricId":"trans_cnt_m",
                        "currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                        "filters":[{"dimensionId":"brand","userTerm":"卡品牌","operator":"EQUALS","values":["银联"],"rationale":"用户限定分析范围","confidence":"HIGH"}],
                        "levels":[{"level":1,"dimensions":[{"dimensionId":"card_attr_def","userTerm":"卡性质","rationale":"用户指定第一层","confidence":"HIGH"}]}],
                        "continuationMode":"STOP","summary":"限定银联卡后按卡性质分析。","unmappedTerms":[],"mappingIssues":[]}
                        """));

        var result = interpreter(llm).interpret(new TemplateChatRequest(
                "user", "conversation", "分析7月对比6月的总交易笔数，只看卡品牌为银联，第一层看卡性质。",
                List.of(), null, "company-model"));

        assertThat(result.status()).isEqualTo("READY_TO_CONFIRM");
        assertThat(result.template().filters()).singleElement().satisfies(filter -> {
            assertThat(filter.dimensionId()).isEqualTo("brand");
            assertThat(filter.dimensionName()).isEqualTo("卡品牌");
            assertThat(filter.operator()).isEqualTo("EQUALS");
            assertThat(filter.values()).containsExactly("银联");
        });
    }

    @Test
    void usesTheCurrentTemplateAndFullConversationToResolveALayerMerge() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString(), eq("company-model")))
                .thenReturn(message("""
                        {"analysisTerms":[
                          {"term":"响应码","level":1,"context":"保留原第一层"},
                          {"term":"有效标识","level":1,"context":"合并原第二层"}],
                        "explicitOrder":true,"requestsAutoExploration":false,"requestsStopAfterTemplate":true,
                        "metricTerm":"总交易笔数","currentPeriod":"2026.7","comparisonPeriod":"2026.5",
                        "filterTerms":[],"unmappedTerms":[]}
                        """))
                .thenReturn(message("""
                        {"name":"交易笔数归因分析","mode":"USER_DEFINED","metricId":"trans_cnt_m",
                        "currentPeriod":"2026.7","comparisonPeriod":"2026.5","filters":[],
                        "levels":[{"level":1,"dimensions":[
                          {"dimensionId":"resp_cde","userTerm":"响应码","rationale":"复用原第一层","confidence":"HIGH"},
                          {"dimensionId":"kpi_ind","userTerm":"有效标识","rationale":"合并原第二层","confidence":"HIGH"}]}],
                        "continuationMode":"STOP","summary":"合并原第一层和第二层。","unmappedTerms":[],"mappingIssues":[]}
                        """));
        DimensionTemplate current = new DimensionTemplate(
                "交易笔数归因分析", "USER_DEFINED", "trans_cnt_m", "总交易笔数", "2026-07", "2026-05", List.of(),
                List.of(
                        new DimensionLayer(1, List.of(new DimensionSelection(
                                "resp_cde", "响应码", "响应码", "用户指定", "", "HIGH"))),
                        new DimensionLayer(2, List.of(new DimensionSelection(
                                "kpi_ind", "有效标识", "有效标识", "用户指定", "", "HIGH")))),
                "STOP", "DRAFT", "两层分析");

        var result = interpreter(llm).interpret(new TemplateChatRequest(
                "user", "conversation", "第一层和第二层合并",
                List.of(
                        new TemplateConversationMessage("user", "第一层看响应码，第二层看有效标识"),
                        new TemplateConversationMessage("assistant", "已生成两层归因模板")),
                current, "company-model"));

        assertThat(result.template().levels()).hasSize(1);
        assertThat(result.template().levels().get(0).dimensions())
                .extracting(DimensionSelection::dimensionId).containsExactly("resp_cde", "kpi_ind");
        assertThat(result.template().comparisonPeriod()).isEqualTo("2026-05");
        assertThat(result.template().currentPeriod()).isEqualTo("2026-07");
        assertThat(result.reply()).contains("分析层级改为第1层[响应码、有效标识]");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<OpenAiCompatibleLlmClient.ChatMessage>> messagesCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).completeWithMessage(messagesCaptor.capture(), anyString(), eq("company-model"));
        String firstPassInput = messagesCaptor.getAllValues().get(0).get(1).content();
        assertThat(firstPassInput).contains("当前完整模板", "当前会话完整历史", "第一层和第二层合并", "resp_cde", "有效标识");
    }

    private AttributionTemplateInterpreter interpreter(OpenAiCompatibleLlmClient llm) {
        return new AttributionTemplateInterpreter(llm, new ObjectMapper());
    }

    private AttributionTemplateInterpreter interpreterAt(String instant, OpenAiCompatibleLlmClient llm) {
        return new AttributionTemplateInterpreter(
                llm, new ObjectMapper(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private LlmResultMessage message(String content) {
        return new LlmResultMessage("company-model", "assistant", content, List.of());
    }
}
