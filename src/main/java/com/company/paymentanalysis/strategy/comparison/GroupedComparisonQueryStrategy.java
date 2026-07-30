package com.company.paymentanalysis.strategy.comparison;

import com.company.paymentanalysis.analysis.ComparisonSubject;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionService;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class GroupedComparisonQueryStrategy implements ComparisonQueryStrategy {

    private final QueryExecutionService executionService;

    public GroupedComparisonQueryStrategy(QueryExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public boolean supports(QueryPlan plan) {
        if (plan == null || plan.comparisonSubjects().size() != 2) {
            return false;
        }
        ComparisonSubject first = plan.comparisonSubjects().get(0);
        ComparisonSubject second = plan.comparisonSubjects().get(1);
        return first.filters().size() == 1
                && second.filters().size() == 1
                && first.filters().get(0).field().equals(second.filters().get(0).field());
    }

    @Override
    public ComparisonRawResult execute(QueryPlan plan) {
        String dimensionCode = ComparisonStrategySupport.comparisonDimension(plan);
        QueryPlan groupedPlan = new QueryPlan(
                IntentType.GROUP_QUERY,
                plan.confidence(),
                plan.metricCode(),
                List.of(dimensionCode),
                plan.filters(),
                plan.comparisonSubjects(),
                plan.requestedCalculations(),
                null,
                true,
                false,
                List.of(),
                "");
        AnalysisExecutionResult execution = executionService.execute(groupedPlan);
        if (execution.status() == ExecutionStatus.QUERY_FAILED) {
            return new ComparisonRawResult(
                    null, null, ExecutionStatus.QUERY_FAILED,
                    execution.queryRecords(), execution.rawData(), execution.warnings());
        }
        List<NormalizedDataRow> rows = ComparisonStrategySupport.rows(execution);
        BigDecimal subjectA = ComparisonStrategySupport.subjectValue(
                rows, plan.comparisonSubjects().get(0), dimensionCode, plan.metricCode());
        BigDecimal subjectB = ComparisonStrategySupport.subjectValue(
                rows, plan.comparisonSubjects().get(1), dimensionCode, plan.metricCode());
        List<String> warnings = new ArrayList<>(execution.warnings());
        if (subjectA == null) {
            warnings.add("对比对象无数据：" + plan.comparisonSubjects().get(0).label());
        }
        if (subjectB == null) {
            warnings.add("对比对象无数据：" + plan.comparisonSubjects().get(1).label());
        }
        ExecutionStatus status = status(subjectA, subjectB);
        return new ComparisonRawResult(
                subjectA, subjectB, status,
                execution.queryRecords(), execution.rawData(), warnings);
    }

    private ExecutionStatus status(BigDecimal subjectA, BigDecimal subjectB) {
        if (subjectA == null && subjectB == null) {
            return ExecutionStatus.NO_DATA;
        }
        if (subjectA == null || subjectB == null) {
            return ExecutionStatus.PARTIAL_SUCCESS;
        }
        return ExecutionStatus.SUCCESS;
    }
}
