package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.attribution.AttributionCatalog;
import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionPolicyProperties;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionRequest;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisLevel;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisPlan;
import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionWorkflowService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ConversationArtifact.VerifiedFact;
import com.company.paymentanalysis.attribution.AttributionWorkflowService.WorkflowEvent;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attribution")
public class AttributionController {

    private static final Set<String> FILTER_OPERATORS = Set.of(
            "EQUALS", "IN", "NOT_EQUALS", "NOT_IN", "GREATER", "GREATER_EQUALS",
            "LESS", "LESS_EQUALS", "BETWEEN", "CONTAINS");

    private final AttributionWorkflowService workflowService;
    private final ChatConversationMemoryService memoryService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final AttributionPolicyProperties policy;
    private final ObjectMapper objectMapper;

    public AttributionController(
            AttributionWorkflowService workflowService,
            ChatConversationMemoryService memoryService,
            OpenAiCompatibleLlmClient llmClient,
            AttributionPolicyProperties policy,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.memoryService = memoryService;
        this.llmClient = llmClient;
        this.policy = policy;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/analyze")
    public AttributionResponse analyze(@RequestBody AttributionRequest request) {
        EffectiveRequest effectiveRequest = validate(request);
        AttributionResponse response = workflowService.analyze(effectiveRequest);
        saveConversationArtifact(request, effectiveRequest, response);
        return response;
    }

    @PostMapping(value = "/analyze/stream", produces = "application/x-ndjson")
    public StreamingResponseBody analyzeStream(@RequestBody AttributionRequest request) {
        EffectiveRequest effectiveRequest = validate(request);
        return output -> streamAnalysis(output, request, effectiveRequest);
    }

    private void streamAnalysis(
            OutputStream output, AttributionRequest sourceRequest, EffectiveRequest request) throws IOException {
        Object writeLock = new Object();
        try {
            AttributionResponse response = workflowService.analyze(request,
                    event -> writeStreamItem(output, writeLock, new AttributionStreamItem("event", event, null, null)));
            saveConversationArtifact(sourceRequest, request, response);
            writeStreamItem(output, writeLock, new AttributionStreamItem("result", null, response, null));
        } catch (Exception exception) {
            String message = rootMessage(exception);
            writeStreamItem(output, writeLock, new AttributionStreamItem(
                    "error",
                    new WorkflowEvent("workflow", "归因分析", "FAILED", message, null, null),
                    null,
                    message));
        }
    }

    private void saveConversationArtifact(
            AttributionRequest request, EffectiveRequest effectiveRequest, AttributionResponse response) {
        String conversationId = safeIdentifier(request.conversationId());
        if (conversationId == null) return;
        String userId = safeIdentifier(request.userId());
        String filters = effectiveRequest.dimensionFilters().stream()
                .map(filter -> filter.dimensionId() + " " + filter.operator() + " " + filter.values())
                .collect(java.util.stream.Collectors.joining("；"));
        String contract = "指标=" + response.metricName() + "(" + response.metricId() + ")；周期="
                + response.currentPeriod() + " 对比 " + response.comparisonPeriod()
                + (filters.isBlank() ? "" : "；过滤=" + filters)
                + "；深度=" + effectiveRequest.maxDepth() + "；最大查询=" + effectiveRequest.maxQueries()
                + "；TopN=" + effectiveRequest.topN();
        String path = response.primaryPath().stream()
                .map(node -> node.dimensionName() + "=" + node.memberValue())
                .collect(java.util.stream.Collectors.joining(" → "));
        String evidence = "主路径=" + (path.isBlank() ? "未形成下钻路径" : path);
        String modelNarrative = "摘要=" + response.report().summary()
                + "；关键发现=" + String.join("；", response.report().findings())
                + "；建议=" + String.join("；", response.report().recommendations());
        Map<String, String> attributes = Map.of(
                "metricId", response.metricId(),
                "metricName", response.metricName(),
                "currentPeriod", response.currentPeriod(),
                "comparisonPeriod", response.comparisonPeriod());
        memoryService.saveAttributionArtifact(
                userId == null ? "demo-user" : userId, conversationId,
                response.metricName() + "归因（" + response.currentPeriod() + " 对比 "
                        + response.comparisonPeriod() + "）",
                response.report().summary(), contract, evidence, attributes,
                verifiedFacts(response), modelNarrative);
    }

    private List<VerifiedFact> verifiedFacts(AttributionResponse response) {
        List<VerifiedFact> facts = new ArrayList<>();
        facts.add(fact("metric", "分析指标", response.metricName(), "REQUEST_VALIDATION"));
        facts.add(fact("period.current", "当前周期", response.currentPeriod(), "REQUEST_VALIDATION"));
        facts.add(fact("period.comparison", "对比周期", response.comparisonPeriod(), "REQUEST_VALIDATION"));
        facts.add(fact("overall.current", "当前值", number(response.overall().currentValue()), "JAVA_OVERALL"));
        facts.add(fact("overall.comparison", "对比值", number(response.overall().comparisonValue()), "JAVA_OVERALL"));
        facts.add(fact("overall.changeAmount", "变化额", number(response.overall().changeAmount()), "JAVA_OVERALL"));
        facts.add(fact("overall.changeRate", "变化率", number(response.overall().changeRate()), "JAVA_OVERALL"));
        facts.add(fact("overall.direction", "变化方向", response.overall().direction(), "JAVA_OVERALL"));
        for (var node : response.primaryPath()) {
            String prefix = "path." + node.depth();
            facts.add(fact(prefix + ".member", "主路径第" + node.depth() + "层",
                    node.dimensionName() + "=" + node.memberValue(), "JAVA_PRIMARY_PATH"));
            facts.add(fact(prefix + ".changeAmount", "该层变化额",
                    number(node.changeAmount()), "JAVA_PRIMARY_PATH"));
            facts.add(fact(prefix + ".contributionRate", "该层贡献率",
                    number(node.contributionRate()), "JAVA_PRIMARY_PATH"));
        }
        response.evidence().stream()
                .filter(item -> "VALID".equals(item.dataStatus()) && item.primaryDriver() != null)
                .forEach(item -> facts.add(fact(
                        "evidence." + item.id() + ".primaryDriver",
                        item.dimensionName() + "主要驱动",
                        item.primaryDriver().memberValue() + "；变化额="
                                + number(item.primaryDriver().changeAmount()) + "；贡献率="
                                + number(item.primaryDriver().contributionRate()),
                        "JAVA_EVIDENCE")));
        return List.copyOf(facts);
    }

    private VerifiedFact fact(String code, String label, String value, String source) {
        return new VerifiedFact(code, label, value, source);
    }

    private String number(java.math.BigDecimal value) {
        return value == null ? "不可用" : value.stripTrailingZeros().toPlainString();
    }

    private String safeIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= 80 && normalized.matches("[A-Za-z0-9._-]+") ? normalized : null;
    }

    private void writeStreamItem(OutputStream output, Object writeLock, AttributionStreamItem item) {
        synchronized (writeLock) {
            try {
                output.write((objectMapper.writeValueAsString(item) + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("归因流式响应写入失败", exception);
            }
        }
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "归因分析执行失败" : current.getMessage();
    }

    @GetMapping("/metadata")
    public AttributionMetadata metadata() {
        return new AttributionMetadata(
                AttributionCatalog.metricIds().stream()
                        .map(id -> new AttributionMetric(id, AttributionCatalog.metricName(id)))
                        .toList(),
                AttributionCatalog.dimensions(),
                new AttributionLimits(
                        2, 3, 8, 12, 5, 10,
                        policy.defaultMaxBranches(), policy.hardMaxBranches(),
                        policy.minAlignedContributionRate(), policy.reservedQueries()));
    }

    private EffectiveRequest validate(AttributionRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        if (!AttributionCatalog.isMetric(request.metricId())) {
            throw badRequest("分析度量必须是允许归因的基础度量");
        }
        YearMonth current = period(request.currentPeriod(), "当前周期");
        YearMonth comparison = period(request.comparisonPeriod(), "对比周期");
        if (!current.isAfter(comparison)) {
            throw badRequest("当前周期必须晚于对比周期");
        }
        for (DimensionFilter filter : request.dimensionFilters()) {
            if (filter == null || !QueryMetadataCatalog.isDimension(filter.dimensionId())) {
                throw badRequest("维度过滤包含非法字段");
            }
            String operator = filter.operator() == null ? "" : filter.operator().toUpperCase();
            if (!FILTER_OPERATORS.contains(operator) || filter.values().isEmpty()) {
                throw badRequest("维度过滤包含非法操作或空值");
            }
            if ("BETWEEN".equals(operator) && filter.values().size() != 2) {
                throw badRequest("BETWEEN 过滤必须提供两个边界值");
            }
        }
        int maxDepth = value(request.maxDepth(), 2);
        int maxQueries = value(request.maxQueries(), 8);
        int topN = value(request.topN(), 5);
        int maxBranches = value(request.maxBranches(), policy.defaultMaxBranches());
        if (maxDepth < 1 || maxDepth > 3) {
            throw badRequest("maxDepth 必须在1至3之间");
        }
        if (maxQueries < 2 || maxQueries > 12) {
            throw badRequest("maxQueries 必须在2至12之间");
        }
        if (topN < 1 || topN > 10) {
            throw badRequest("topN 必须在1至10之间");
        }
        if (maxBranches < 1 || maxBranches > policy.hardMaxBranches()) {
            throw badRequest("maxBranches 超出允许范围");
        }
        List<DimensionFilter> filters = request.dimensionFilters().stream()
                .map(filter -> new DimensionFilter(
                        filter.dimensionId(), filter.operator().toUpperCase(), filter.values()))
                .toList();
        AnalysisPlan analysisPlan = validateAnalysisPlan(request.analysisPlan(), filters, maxDepth, maxQueries);
        String model;
        try {
            model = llmClient.resolveSelection(request.model());
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        return new EffectiveRequest(
                request.metricId(),
                current.toString(),
                comparison.toString(),
                filters,
                analysisPlan,
                maxDepth,
                maxQueries,
                topN,
                maxBranches,
                model);
    }

    private AnalysisPlan validateAnalysisPlan(
            AnalysisPlan plan, List<DimensionFilter> filters, int maxDepth, int maxQueries) {
        if (plan == null || plan.levels().isEmpty()) return null;
        if (plan.levels().size() > maxDepth) {
            throw badRequest("最大下钻深度不能小于模板层数");
        }
        if (plan.levels().get(0).dimensionIds().size() + 1 > maxQueries) {
            throw badRequest("查询次数不足以执行模板第一层全部并行维度");
        }
        Set<String> filterIds = filters.stream().map(DimensionFilter::dimensionId)
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> used = new LinkedHashSet<>();
        java.util.ArrayList<AnalysisLevel> levels = new java.util.ArrayList<>();
        for (int index = 0; index < plan.levels().size(); index++) {
            AnalysisLevel level = plan.levels().get(index);
            if (level == null || level.level() != index + 1 || level.dimensionIds().isEmpty()
                    || level.dimensionIds().size() > 5) {
                throw badRequest("模板层级必须连续且每层包含1至5个维度");
            }
            for (String dimensionId : level.dimensionIds()) {
                if (!AttributionCatalog.isDimension(dimensionId) || !used.add(dimensionId)) {
                    throw badRequest("模板包含非法或重复维度");
                }
                if (filterIds.contains(dimensionId)) {
                    throw badRequest("过滤维度不能同时作为模板下钻维度");
                }
            }
            levels.add(new AnalysisLevel(level.level(), level.dimensionIds()));
        }
        return new AnalysisPlan(List.copyOf(levels), plan.continueExploration());
    }

    private YearMonth period(String value, String name) {
        try {
            return YearMonth.parse(value);
        } catch (Exception exception) {
            throw badRequest(name + "格式必须为 yyyy-MM");
        }
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AttributionMetric(String id, String name) {
    }

    public record AttributionLimits(
            int defaultMaxDepth,
            int hardMaxDepth,
            int defaultMaxQueries,
            int hardMaxQueries,
            int defaultTopN,
            int hardTopN,
            int defaultMaxBranches,
            int hardMaxBranches,
            java.math.BigDecimal minAlignedContributionRate,
            int reservedQueries) {
    }

    public record AttributionMetadata(
            List<AttributionMetric> metrics,
            List<AttributionDimension> dimensions,
            AttributionLimits limits) {
    }

    public record AttributionStreamItem(
            String type, WorkflowEvent event, AttributionResponse result, String message) {
    }
}
