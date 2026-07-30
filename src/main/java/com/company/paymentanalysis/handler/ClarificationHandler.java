package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ClarificationHandler implements IntentHandler {

    @Override
    public IntentType support() {
        return IntentType.CLARIFICATION;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan plan, AnalysisContext context) {
        if (plan == null || plan.intent() != support()) {
            throw new IllegalArgumentException("ClarificationHandler 只处理 CLARIFICATION");
        }
        return new AnalysisExecutionResult(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                support(),
                ExecutionStatus.VALIDATION_FAILED,
                List.of(),
                null,
                null,
                plan.missingSlots(),
                plan.clarificationQuestion());
    }
}
