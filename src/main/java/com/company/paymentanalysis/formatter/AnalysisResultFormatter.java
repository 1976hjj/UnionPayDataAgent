package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;

public interface AnalysisResultFormatter {

    IntentType support();

    String format(AnalysisExecutionResult result, QueryPlan plan);
}
