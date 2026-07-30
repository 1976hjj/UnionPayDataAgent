package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;

public interface IntentHandler {

    IntentType support();

    AnalysisExecutionResult execute(QueryPlan queryPlan, AnalysisContext context);
}
