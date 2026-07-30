package com.company.paymentanalysis.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueryPlanValidator {

    private static final Set<IntentType> STAGE_ONE_INTENTS = Set.of(
            IntentType.SINGLE_QUERY,
            IntentType.GROUP_QUERY,
            IntentType.COMPARE_QUERY,
            IntentType.CLARIFICATION,
            IntentType.OUT_OF_SCOPE);

    public QueryPlan validate(QueryPlan plan) {
        if (plan == null || plan.intent() == null) {
            throw new IllegalArgumentException("QueryPlan 和 intent 不能为空");
        }
        if (plan.intent() == IntentType.CLARIFICATION) {
            if (!plan.clarificationQuestion().isBlank()) {
                return plan;
            }
            LinkedHashSet<String> missing = new LinkedHashSet<>(plan.missingSlots());
            if (missing.isEmpty()) {
                missing.add("queryConditions");
            }
            return clarification(plan, List.copyOf(missing), questionFor(missing));
        }
        if (plan.intent() == IntentType.OUT_OF_SCOPE) {
            return plan;
        }
        if (!STAGE_ONE_INTENTS.contains(plan.intent())) {
            return clarification(
                    plan,
                    List.of("unsupportedIntent"),
                    "第一阶段暂不执行“" + plan.intent() + "”，请先改为单项、分组或双对象对比查询。");
        }

        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (plan.metricCode().isBlank()) {
            missing.add("metric");
        }
        if (plan.intent() == IntentType.GROUP_QUERY && plan.dimensionCodes().isEmpty()) {
            missing.add("dimension");
        }
        if (plan.intent() == IntentType.COMPARE_QUERY) {
            if (plan.comparisonSubjects().size() != 2) {
                missing.add("comparisonSubjects");
            } else if (plan.comparisonSubjects().stream()
                    .anyMatch(subject -> subject.filters().isEmpty())) {
                missing.add("comparisonSubjects.filters");
            }
        }
        if (!hasTime(plan)) {
            missing.add("time");
        }
        if (!missing.isEmpty()) {
            return clarification(plan, List.copyOf(missing), questionFor(missing));
        }
        return new QueryPlan(
                plan.intent(),
                plan.confidence(),
                plan.metricCode(),
                plan.dimensionCodes(),
                plan.filters(),
                plan.comparisonSubjects(),
                plan.requestedCalculations(),
                plan.topN(),
                true,
                plan.needsKnowledgeBase(),
                List.of(),
                "");
    }

    private boolean hasTime(QueryPlan plan) {
        return plan.filters().stream().anyMatch(this::isTime)
                || plan.comparisonSubjects().stream()
                        .flatMap(subject -> subject.filters().stream())
                        .anyMatch(this::isTime);
    }

    private boolean isTime(FilterCondition filter) {
        return Set.of("tradeDate", "tradeMonth", "tradeYear").contains(filter.field());
    }

    private QueryPlan clarification(QueryPlan plan, List<String> missing, String question) {
        List<String> combined = new ArrayList<>(plan.missingSlots());
        combined.addAll(missing);
        return new QueryPlan(
                IntentType.CLARIFICATION,
                plan.confidence(),
                plan.metricCode(),
                plan.dimensionCodes(),
                plan.filters(),
                plan.comparisonSubjects(),
                plan.requestedCalculations(),
                plan.topN(),
                false,
                plan.needsKnowledgeBase(),
                combined.stream().distinct().toList(),
                question);
    }

    private String questionFor(Set<String> missing) {
        if (missing.contains("metric")) {
            return "请指定要查询的指标，例如人民币总金额、交易笔数或支付成功率。";
        }
        if (missing.contains("dimension")) {
            return "请指定分组维度，例如收单地区、渠道、年、月或日。";
        }
        if (missing.stream().anyMatch(value -> value.startsWith("comparisonSubjects"))) {
            return "请明确两个需要对比的对象。";
        }
        if (missing.contains("time")) {
            return "请指定查询时间范围。";
        }
        return "请补充需要查询的指标、时间或分组条件。";
    }
}
