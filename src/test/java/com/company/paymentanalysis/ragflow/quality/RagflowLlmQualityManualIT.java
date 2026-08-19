package com.company.paymentanalysis.ragflow.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionSelection;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateInterpreter;
import com.company.paymentanalysis.chat.ChatQueryInterpreter;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Deliberately opt-in quality checks: real GLM-5.2 plus the workbook-backed
 * RAG mock. The class name ends in IT, so Surefire does not run it by default.
 */
@SpringBootTest(properties = {
        "llm.mock-enabled=false",
        "ragflow.enabled=false",
        "ragflow.mock-enabled=true"
})
@EnabledIfEnvironmentVariable(named = "RUN_RAGFLOW_LLM_QUALITY_TESTS", matches = "true")
class RagflowLlmQualityManualIT {

    @Autowired
    private ChatQueryInterpreter queryInterpreter;

    @Autowired
    private AttributionTemplateInterpreter attributionInterpreter;

    @Test
    void resolvesMetricGroupingAndFilterFromAnOrdinaryQueryPhrase() {
        var result = queryInterpreter.interpret(
                new ChatRequest("quality", "query-quality", "帮我看乌拉圭的人民币总金额，按卡品牌分组。",
                        QueryContext.empty(), "glm-5.2", false),
                QueryContext.empty());

        System.out.println("QUERY QUALITY RESULT: " + result.action());
        assertThat(result.action().metricIds()).contains("trans_rmb_amt_m");
        assertThat(result.action().dimensionIds()).contains("brand");
        assertThat(result.action().dimensionFilters())
                .anySatisfy(filter -> assertThat(filter.dimensionId()).isEqualTo("acq_mkt_ch"));
    }

    @Test
    void resolvesTheBusinessAliasTransactionAmountFromTheWorkbook() {
        var result = queryInterpreter.interpret(
                new ChatRequest("quality", "query-alias-quality", "看一下乌拉圭的交易额，按卡品牌看。",
                        QueryContext.empty(), "glm-5.2", false),
                QueryContext.empty());

        System.out.println("QUERY ALIAS QUALITY RESULT: " + result.action());
        assertThat(result.action().metricIds()).contains("trans_rmb_amt_m");
        assertThat(result.action().dimensionIds()).contains("brand");
        assertThat(result.action().dimensionFilters())
                .anySatisfy(filter -> assertThat(filter.dimensionId()).isEqualTo("acq_mkt_ch"));
    }

    @Test
    void resolvesAttributionMetricLayersAndFilterFromOneSentence() {
        var result = attributionInterpreter.interpret(new TemplateChatRequest(
                "quality", "attribution-quality",
                "分析2026年7月对比2025年7月的人民币总金额，先按卡品牌，再按收单市场，并限定乌拉圭，完成模板后停止。",
                List.of(), null, "glm-5.2"));

        System.out.println("ATTRIBUTION QUALITY RESULT: " + result.template());
        assertThat(result.template().metricId()).isEqualTo("trans_rmb_amt_m");
        assertThat(result.template().levels()).flatExtracting(level -> level.dimensions())
                .extracting(DimensionSelection::dimensionId).contains("brand", "acq_mkt_ch");
        assertThat(result.template().filters())
                .anySatisfy(filter -> assertThat(filter.dimensionId()).isEqualTo("acq_mkt_ch"));
    }
}
