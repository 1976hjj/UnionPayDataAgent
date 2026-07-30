package com.company.paymentanalysis.strategy.comparison;

import com.company.paymentanalysis.analysis.ComparisonSubject;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionRecord;
import com.company.paymentanalysis.execution.QueryExecutionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
public class SeparateComparisonQueryStrategy implements ComparisonQueryStrategy {

    private final QueryExecutionService executionService;

    public SeparateComparisonQueryStrategy(QueryExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public boolean supports(QueryPlan queryPlan) {
        return queryPlan != null && queryPlan.comparisonSubjects().size() == 2;
    }

    @Override
    public ComparisonRawResult execute(QueryPlan plan) {
        AnalysisExecutionResult first = executeSubject(plan, plan.comparisonSubjects().get(0));
        AnalysisExecutionResult second = executeSubject(plan, plan.comparisonSubjects().get(1));
        BigDecimal subjectA = value(first, plan.metricCode());
        BigDecimal subjectB = value(second, plan.metricCode());
        List<QueryExecutionRecord> records = new ArrayList<>();
        records.addAll(first.queryRecords());
        records.addAll(second.queryRecords());
        List<String> warnings = new ArrayList<>();
        warnings.addAll(first.warnings());
        warnings.addAll(second.warnings());
        if (subjectA == null) {
            warnings.add("对比对象无数据：" + plan.comparisonSubjects().get(0).label());
        }
        if (subjectB == null) {
            warnings.add("对比对象无数据：" + plan.comparisonSubjects().get(1).label());
        }
        ExecutionStatus status;
        if (first.status() == ExecutionStatus.QUERY_FAILED
                && second.status() == ExecutionStatus.QUERY_FAILED) {
            status = ExecutionStatus.QUERY_FAILED;
        } else if (subjectA == null && subjectB == null) {
            status = ExecutionStatus.NO_DATA;
        } else if (subjectA == null || subjectB == null
                || first.status() == ExecutionStatus.QUERY_FAILED
                || second.status() == ExecutionStatus.QUERY_FAILED) {
            status = ExecutionStatus.PARTIAL_SUCCESS;
        } else {
            status = ExecutionStatus.SUCCESS;
        }
        return new ComparisonRawResult(
                subjectA,
                subjectB,
                status,
                records,
                java.util.Arrays.asList(first.rawData(), second.rawData()),
                warnings);
    }

    private AnalysisExecutionResult executeSubject(
            QueryPlan plan,
            ComparisonSubject subject) {
        List<com.company.paymentanalysis.analysis.FilterCondition> filters =
                new ArrayList<>(plan.filters());
        filters.addAll(subject.filters());
        QueryPlan subjectPlan = new QueryPlan(
                IntentType.SINGLE_QUERY,
                plan.confidence(),
                plan.metricCode(),
                List.of(),
                filters,
                List.of(),
                plan.requestedCalculations(),
                null,
                true,
                false,
                List.of(),
                "");
        return executionService.execute(subjectPlan);
    }

    private BigDecimal value(AnalysisExecutionResult execution, String metricCode) {
        return ComparisonStrategySupport.sumMetric(
                ComparisonStrategySupport.rows(execution),
                metricCode);
    }
}
