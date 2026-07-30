package com.company.paymentanalysis.strategy.comparison;

import com.company.paymentanalysis.analysis.QueryPlan;

public interface ComparisonQueryStrategy {

    boolean supports(QueryPlan queryPlan);

    ComparisonRawResult execute(QueryPlan queryPlan);
}
