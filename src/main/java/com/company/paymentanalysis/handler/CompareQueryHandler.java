package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.AnalysisCalculationEngine;
import com.company.paymentanalysis.calculation.CalculationType;
import com.company.paymentanalysis.calculation.ComparisonExpression;
import com.company.paymentanalysis.calculation.ComparisonRequest;
import com.company.paymentanalysis.calculation.ComparisonResult;
import com.company.paymentanalysis.config.AnalysisProperties;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.strategy.comparison.ComparisonQueryStrategy;
import com.company.paymentanalysis.strategy.comparison.ComparisonRawResult;
import com.company.paymentanalysis.strategy.comparison.GroupedComparisonQueryStrategy;
import com.company.paymentanalysis.strategy.comparison.SeparateComparisonQueryStrategy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CompareQueryHandler implements IntentHandler {

    private final List<ComparisonQueryStrategy> strategies;
    private final AnalysisCalculationEngine calculationEngine;
    private final AnalysisProperties properties;

    public CompareQueryHandler(
            List<ComparisonQueryStrategy> strategies,
            AnalysisCalculationEngine calculationEngine,
            AnalysisProperties properties) {
        this.strategies = List.copyOf(strategies);
        this.calculationEngine = calculationEngine;
        this.properties = properties;
    }

    @Override
    public IntentType support() {
        return IntentType.COMPARE_QUERY;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan plan, AnalysisContext context) {
        validate(plan);
        ComparisonQueryStrategy strategy = selectStrategy(plan);
        ComparisonRawResult raw = strategy.execute(plan);
        List<String> auditWarnings = new java.util.ArrayList<>(raw.warnings());
        auditWarnings.add("对比查询策略：" + strategy.getClass().getSimpleName());
        ComparisonResult calculated = null;
        ExecutionStatus status = raw.status();
        if (raw.subjectAValue() != null && raw.subjectBValue() != null) {
            calculated = calculationEngine.compare(
                    raw.subjectAValue(),
                    raw.subjectBValue(),
                    new ComparisonRequest(
                            plan.comparisonSubjects().get(0).label(),
                            plan.comparisonSubjects().get(1).label(),
                            expression(plan.requestedCalculations()),
                            calculations(plan.requestedCalculations()),
                            properties.calculation().rateScale(),
                            properties.calculation().roundingMode()));
            status = ExecutionStatus.SUCCESS;
        }
        return new AnalysisExecutionResult(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                support(),
                status,
                raw.queryRecords(),
                raw.rawData(),
                calculated,
                List.copyOf(auditWarnings),
                "");
    }

    private ComparisonQueryStrategy selectStrategy(QueryPlan plan) {
        if (properties.comparison().preferGroupedQuery()) {
            ComparisonQueryStrategy grouped = strategies.stream()
                    .filter(GroupedComparisonQueryStrategy.class::isInstance)
                    .filter(candidate -> candidate.supports(plan))
                    .findFirst()
                    .orElse(null);
            if (grouped != null) {
                return grouped;
            }
            if (!properties.comparison().fallbackToSeparateQuery()) {
                throw new IllegalStateException("分组对比策略不适用，且已禁用独立查询回退");
            }
        }
        return strategies.stream()
                .filter(SeparateComparisonQueryStrategy.class::isInstance)
                .filter(candidate -> candidate.supports(plan))
                .findFirst()
                .orElseGet(() -> strategies.stream()
                        .filter(candidate -> candidate.supports(plan))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("没有可用的对比查询策略")));
    }

    private void validate(QueryPlan plan) {
        if (plan == null || plan.intent() != support()) {
            throw new IllegalArgumentException("CompareQueryHandler 只处理 COMPARE_QUERY");
        }
        if (plan.metricCode().isBlank()) {
            throw new IllegalArgumentException("对比查询指标不能为空");
        }
        if (plan.comparisonSubjects().size() != 2) {
            throw new IllegalArgumentException("对比查询必须包含两个对比对象");
        }
    }

    private ComparisonExpression expression(List<String> requested) {
        String joined = String.join(" ", requested).toUpperCase(Locale.ROOT);
        if (joined.contains("LESS") || joined.contains("少")) {
            return ComparisonExpression.A_LESS_THAN_B;
        }
        if (joined.contains("MORE") || joined.contains("多")) {
            return ComparisonExpression.A_MORE_THAN_B;
        }
        if (joined.contains("B_MINUS_A")) {
            return ComparisonExpression.B_MINUS_A;
        }
        if (joined.contains("A_MINUS_B")) {
            return ComparisonExpression.A_MINUS_B;
        }
        return ComparisonExpression.COMPARE_ONLY;
    }

    private List<CalculationType> calculations(List<String> requested) {
        String joined = String.join(" ", requested).toUpperCase(Locale.ROOT);
        java.util.ArrayList<CalculationType> result =
                new java.util.ArrayList<>(List.of(
                        CalculationType.ABSOLUTE_DIFFERENCE,
                        CalculationType.RELATION));
        if (joined.contains("RATE")
                || joined.contains("PERCENT")
                || joined.contains("增长")
                || joined.contains("百分")) {
            result.add(CalculationType.CHANGE_RATE);
        }
        return List.copyOf(result);
    }
}
