package com.company.paymentanalysis.controller;

import static com.company.paymentanalysis.attribution.AttributionCatalog.DIMENSION_NAMES;
import static com.company.paymentanalysis.attribution.AttributionCatalog.METRIC_NAMES;

import com.company.paymentanalysis.attribution.AttributionWorkflowService;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import java.io.Serializable;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attribution")
public class AttributionController {

    private final AttributionWorkflowService workflowService;

    public AttributionController(AttributionWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/analyze")
    public AttributionResponse analyze(@RequestBody AttributionRequest request) {
        validate(request);
        return workflowService.analyze(request);
    }

    private void validate(AttributionRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        if (!METRIC_NAMES.containsKey(request.metricCode())) {
            throw badRequest("分析度量无效");
        }
        try {
            YearMonth.parse(request.currentPeriod());
        } catch (Exception exception) {
            throw badRequest("当前周期格式无效");
        }
        if (!Set.of("monthOnMonth", "yearOnYear").contains(request.comparisonType())) {
            throw badRequest("对比方式无效");
        }
        if (!DIMENSION_NAMES.containsKey(request.level1DimensionCode())) {
            throw badRequest("一级维度无效");
        }
        List<String> level2Codes = request.level2DimensionCodes();
        if (level2Codes == null || level2Codes.isEmpty() || level2Codes.size() > 3) {
            throw badRequest("二级维度必须选择1至3个");
        }
        if (new LinkedHashSet<>(level2Codes).size() != level2Codes.size()) {
            throw badRequest("二级维度不允许重复");
        }
        if (level2Codes.contains(request.level1DimensionCode())) {
            throw badRequest("二级维度不能包含一级维度");
        }
        if (level2Codes.stream().anyMatch(code -> !DIMENSION_NAMES.containsKey(code))) {
            throw badRequest("二级维度无效");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AttributionRequest(
            String metricCode,
            String currentPeriod,
            String comparisonType,
            String level1DimensionCode,
            List<String> level2DimensionCodes,
            String businessScope) implements Serializable {
    }

    public record AttributionResponse(
            String metricName,
            String currentPeriod,
            String comparisonPeriod,
            String overallChange,
            String level1DimensionCode,
            String level1DimensionName,
            DriverMember level1Driver,
            Map<String, ContributionResult> level2Results,
            int totalQueryCount,
            boolean periodsCombinedInSingleQuery,
            String reportNotice,
            String executionEngine,
            List<WorkflowStep> workflowSteps,
            List<QueryTrace> smartBiQueries,
            LlmResultMessage llmMessage) implements Serializable {
    }

    public record WorkflowStep(
            String node,
            String name,
            String status,
            String detail) implements Serializable {
    }

    public record DriverMember(
            String memberCode,
            String memberName,
            String absoluteChangeAmount,
            String direction,
            String selectionReason) implements Serializable {
    }

    public record ContributionResult(
            String dimensionCode,
            String dimensionName,
            List<MemberContribution> members) implements Serializable {
    }

    public record MemberContribution(
            String memberName,
            String currentValue,
            String comparisonValue,
            String changeAmount,
            String changeRate,
            double contributionRate,
            String direction) implements Serializable {
    }
}
