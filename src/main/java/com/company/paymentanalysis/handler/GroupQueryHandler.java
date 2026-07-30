package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.QueryExecutionService;
import org.springframework.stereotype.Component;

@Component
public class GroupQueryHandler implements IntentHandler {

    private final QueryExecutionService queryExecutionService;

    public GroupQueryHandler(QueryExecutionService queryExecutionService) {
        this.queryExecutionService = queryExecutionService;
    }

    @Override
    public IntentType support() {
        return IntentType.GROUP_QUERY;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan queryPlan, AnalysisContext context) {
        requireSupportedIntent(queryPlan);
        return queryExecutionService.execute(queryPlan);
    }

    private void requireSupportedIntent(QueryPlan queryPlan) {
        if (queryPlan == null || queryPlan.intent() != support()) {
            throw new IllegalArgumentException("GroupQueryHandler 只处理 GROUP_QUERY");
        }
    }
}
