package com.company.paymentanalysis.smartbi;

import static com.company.paymentanalysis.attribution.AttributionCatalog.DIMENSION_FIELDS;

import com.company.paymentanalysis.controller.AttributionController.AttributionRequest;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SmartBiQueryTranslator {

    private static final Map<String, String> BASE_METRIC_FIELDS = Map.of(
            "rmbAmount", "acpt_trans_rmb_amt_m",
            "transactionCount", "acpt_trans_cnt_m",
            "successRate", "pay_success_rate_m");

    private final OpenAiCompatibleLlmClient llmClient;
    private final SmartBiProperties smartBiProperties;
    private final ObjectMapper objectMapper;

    public SmartBiQueryTranslator(
            OpenAiCompatibleLlmClient llmClient,
            SmartBiProperties smartBiProperties,
            ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.smartBiProperties = smartBiProperties;
        this.objectMapper = objectMapper;
    }

    public TranslationResult translate(AttributionRequest request) {
        String baseMetricField = BASE_METRIC_FIELDS.get(request.metricCode());
        String comparisonMetricField = baseMetricField.substring(0, baseMetricField.length() - 1)
                + ("yearOnYear".equals(request.comparisonType()) ? "tb" : "hb");
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put(request.level1DimensionCode(), DIMENSION_FIELDS.get(request.level1DimensionCode()));
        request.level2DimensionCodes().forEach(code -> dimensions.put(code, DIMENSION_FIELDS.get(code)));

        TranslatedQueryPlan mockPlan = new TranslatedQueryPlan(
                smartBiProperties.datasetId(),
                "sett_dt_Month2",
                baseMetricField,
                comparisonMetricField,
                dimensions,
                "biz_scope");
        try {
            String mockJson = objectMapper.writeValueAsString(mockPlan);
            LlmResultMessage llmMessage = llmClient.completeWithMessage(
                    List.of(
                            new ChatMessage(
                                    "system",
                                    """
                                    你是支付数据查询的 SmartBI 字段映射器。
                                    只能返回一个 JSON 对象，不要解释，不要 Markdown。
                                    输出必须包含且仅包含：
                                    dataSetId、periodField、metricField、comparisonMetricField、
                                    dimensionFields、businessScopeField。
                                    必须从用户提供的候选映射中取值，不允许创造字段。
                                    """),
                            new ChatMessage(
                                    "user",
                                    "业务请求："
                                            + objectMapper.writeValueAsString(request)
                                            + "\n允许且期望的字段映射："
                                            + mockJson)),
                    mockJson);
            TranslatedQueryPlan translated =
                    objectMapper.readValue(stripMarkdownFence(llmMessage.content()), TranslatedQueryPlan.class);
            validate(translated, request);
            return new TranslationResult(translated, llmMessage);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SmartBI 查询映射 JSON 无法解析", exception);
        }
    }

    private void validate(TranslatedQueryPlan plan, AttributionRequest request) {
        if (!smartBiProperties.datasetId().equals(plan.dataSetId())
                || plan.dimensionFields() == null
                || !plan.dimensionFields().keySet().contains(request.level1DimensionCode())
                || !plan.dimensionFields().keySet().containsAll(request.level2DimensionCodes())) {
            throw new IllegalStateException("LLM 返回了候选范围之外的 SmartBI 字段映射");
        }
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    public boolean usesRealLlm() {
        return !llmClient.isMockEnabled();
    }

    private String stripMarkdownFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    public record TranslatedQueryPlan(
            String dataSetId,
            String periodField,
            String metricField,
            String comparisonMetricField,
            Map<String, String> dimensionFields,
            String businessScopeField) implements Serializable {
    }

    public record TranslationResult(TranslatedQueryPlan plan, LlmResultMessage llmMessage) {
    }
}
