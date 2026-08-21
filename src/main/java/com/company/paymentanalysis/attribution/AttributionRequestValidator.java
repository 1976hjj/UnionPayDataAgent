package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionModels.AnalysisLevel;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisPlan;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionRequest;
import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Validates every attribution request before it reaches the workflow. */
@Service
public class AttributionRequestValidator {

    private static final Set<String> FILTER_OPERATORS = Set.of(
            "EQUALS", "IN", "NOT_EQUALS", "NOT_IN", "GREATER", "GREATER_EQUALS",
            "LESS", "LESS_EQUALS", "BETWEEN", "CONTAINS");

    private final OpenAiCompatibleLlmClient llmClient;
    private final AttributionPolicyProperties policy;

    public AttributionRequestValidator(OpenAiCompatibleLlmClient llmClient, AttributionPolicyProperties policy) {
        this.llmClient = llmClient;
        this.policy = policy;
    }

    public EffectiveRequest validate(AttributionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("归因请求不能为空");
        }
        if (!AttributionCatalog.isMetric(request.metricId())) {
            throw new IllegalArgumentException("分析度量必须是允许归因的基础度量");
        }
        YearMonth current = period(request.currentPeriod(), "当前周期");
        YearMonth comparison = period(request.comparisonPeriod(), "对比周期");
        if (!current.isAfter(comparison)) {
            throw new IllegalArgumentException("当前周期必须晚于对比周期");
        }
        for (DimensionFilter filter : request.dimensionFilters()) {
            if (filter == null || !QueryMetadataCatalog.isDimension(filter.dimensionId())) {
                throw new IllegalArgumentException("维度过滤包含非法字段");
            }
            String operator = filter.operator() == null ? "" : filter.operator().toUpperCase();
            if (!FILTER_OPERATORS.contains(operator) || filter.values().isEmpty()) {
                throw new IllegalArgumentException("维度过滤包含非法操作或空值");
            }
            if ("BETWEEN".equals(operator) && filter.values().size() != 2) {
                throw new IllegalArgumentException("BETWEEN 过滤必须提供两个边界值");
            }
        }
        int maxDepth = value(request.maxDepth(), 2);
        int maxQueries = value(request.maxQueries(), 8);
        int topN = value(request.topN(), 5);
        int maxBranches = value(request.maxBranches(), policy.defaultMaxBranches());
        if (maxDepth < 1 || maxDepth > 3) {
            throw new IllegalArgumentException("maxDepth 必须在 1 至 3 之间");
        }
        if (maxQueries < 2 || maxQueries > 12) {
            throw new IllegalArgumentException("maxQueries 必须在 2 至 12 之间");
        }
        if (topN < 1 || topN > 10) {
            throw new IllegalArgumentException("topN 必须在 1 至 10 之间");
        }
        if (maxBranches < 1 || maxBranches > policy.hardMaxBranches()) {
            throw new IllegalArgumentException("maxBranches 超出允许范围");
        }
        List<DimensionFilter> filters = request.dimensionFilters().stream()
                .map(filter -> new DimensionFilter(
                        filter.dimensionId(), filter.operator().toUpperCase(), filter.values()))
                .toList();
        AnalysisPlan analysisPlan = validateAnalysisPlan(request.analysisPlan(), filters, maxDepth, maxQueries);
        return new EffectiveRequest(
                request.metricId(), current.toString(), comparison.toString(), filters, analysisPlan,
                maxDepth, maxQueries, topN, maxBranches, llmClient.resolveSelection(request.model()),
                request.userId());
    }

    private AnalysisPlan validateAnalysisPlan(
            AnalysisPlan plan, List<DimensionFilter> filters, int maxDepth, int maxQueries) {
        if (plan == null || plan.levels().isEmpty()) return null;
        if (plan.levels().size() > maxDepth) {
            throw new IllegalArgumentException("最大下钻深度不能小于模板层数");
        }
        if (plan.levels().get(0).dimensionIds().size() + 1 > maxQueries) {
            throw new IllegalArgumentException("查询次数不足以执行模板第一层全部并行维度");
        }
        Set<String> filterIds = filters.stream().map(DimensionFilter::dimensionId)
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> used = new LinkedHashSet<>();
        ArrayList<AnalysisLevel> levels = new ArrayList<>();
        for (int index = 0; index < plan.levels().size(); index++) {
            AnalysisLevel level = plan.levels().get(index);
            if (level == null || level.level() != index + 1 || level.dimensionIds().isEmpty()
                    || level.dimensionIds().size() > 5) {
                throw new IllegalArgumentException("模板层级必须连续且每层包含 1 至 5 个维度");
            }
            for (String dimensionId : level.dimensionIds()) {
                if (!AttributionCatalog.isDimension(dimensionId) || !used.add(dimensionId)) {
                    throw new IllegalArgumentException("模板包含非法或重复维度");
                }
                if (filterIds.contains(dimensionId)) {
                    throw new IllegalArgumentException("过滤维度不能同时作为模板下钻维度");
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
            throw new IllegalArgumentException(name + "格式必须为 yyyy-MM");
        }
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
