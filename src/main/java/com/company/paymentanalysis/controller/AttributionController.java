package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.attribution.AttributionCatalog;
import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionPolicyProperties;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionRequest;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionWorkflowService;
import com.company.paymentanalysis.attribution.AttributionWorkflowService.WorkflowEvent;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
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
    private final OpenAiCompatibleLlmClient llmClient;
    private final AttributionPolicyProperties policy;
    private final ObjectMapper objectMapper;

    public AttributionController(
            AttributionWorkflowService workflowService,
            OpenAiCompatibleLlmClient llmClient,
            AttributionPolicyProperties policy,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.llmClient = llmClient;
        this.policy = policy;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/analyze")
    public AttributionResponse analyze(@RequestBody AttributionRequest request) {
        return workflowService.analyze(validate(request));
    }

    @PostMapping(value = "/analyze/stream", produces = "application/x-ndjson")
    public StreamingResponseBody analyzeStream(@RequestBody AttributionRequest request) {
        EffectiveRequest effectiveRequest = validate(request);
        return output -> streamAnalysis(output, effectiveRequest);
    }

    private void streamAnalysis(OutputStream output, EffectiveRequest request) throws IOException {
        Object writeLock = new Object();
        try {
            AttributionResponse response = workflowService.analyze(request,
                    event -> writeStreamItem(output, writeLock, new AttributionStreamItem("event", event, null, null)));
            writeStreamItem(output, writeLock, new AttributionStreamItem("result", null, response, null));
        } catch (Exception exception) {
            String message = rootMessage(exception);
            writeStreamItem(output, writeLock, new AttributionStreamItem(
                    "error",
                    new WorkflowEvent("workflow", "归因分析", "FAILED", message, null),
                    null,
                    message));
        }
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
                maxDepth,
                maxQueries,
                topN,
                maxBranches,
                model);
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
