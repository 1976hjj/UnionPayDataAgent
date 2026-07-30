package com.company.paymentanalysis.execution;

import com.company.paymentanalysis.analysis.QueryPlan;

public interface QueryExecutionService {

    AnalysisExecutionResult execute(QueryPlan queryPlan);
}
