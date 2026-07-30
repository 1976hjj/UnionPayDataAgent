package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.QueryExecutionService;
import org.springframework.stereotype.Component;

@Component
public class SingleQueryHandler implements IntentHandler {

    private final QueryExecutionService queryExecutionService;

    public SingleQueryHandler(QueryExecutionService queryExecutionService) {
        this.queryExecutionService = queryExecutionService;
    }

    @Override
    public IntentType support() {
        return IntentType.SINGLE_QUERY;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan queryPlan, AnalysisContext context) {
        requireSupportedIntent(queryPlan);
        return queryExecutionService.execute(queryPlan);
    }

    private void requireSupportedIntent(QueryPlan queryPlan) {
        if (queryPlan == null || queryPlan.intent() != support()) {
            throw new IllegalArgumentException("SingleQueryHandler 只处理 SINGLE_QUERY");
        }
    }
}
