package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;

public abstract class StandardQueryResultFormatter implements AnalysisResultFormatter {

    private final IntentType intent;

    protected StandardQueryResultFormatter(IntentType intent) {
        this.intent = intent;
    }

    @Override
    public IntentType support() {
        return intent;
    }

    @Override
    public String format(AnalysisExecutionResult result, QueryPlan plan) {
        int count = result.calculationResult() instanceof java.util.List<?> rows
                ? rows.size()
                : 0;
        return count == 0 ? "查询没有匹配数据。" : "查询完成，共返回" + count + "条数据。";
    }
}
