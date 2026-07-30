package com.company.paymentanalysis.analysis;

import java.io.Serializable;
import java.util.List;

public record QueryPlan(
        IntentType intent,
        double confidence,
        String metricCode,
        List<String> dimensionCodes,
        List<FilterCondition> filters,
        List<ComparisonSubject> comparisonSubjects,
        List<String> requestedCalculations,
        Integer topN,
        boolean needsDataQuery,
        boolean needsKnowledgeBase,
        List<String> missingSlots,
        String clarificationQuestion) implements Serializable {

    public QueryPlan {
        metricCode = metricCode == null ? "" : metricCode;
        dimensionCodes = dimensionCodes == null ? List.of() : List.copyOf(dimensionCodes);
        filters = filters == null ? List.of() : List.copyOf(filters);
        comparisonSubjects =
                comparisonSubjects == null ? List.of() : List.copyOf(comparisonSubjects);
        requestedCalculations =
                requestedCalculations == null ? List.of() : List.copyOf(requestedCalculations);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
    }

    public boolean isClarification() {
        return intent == IntentType.CLARIFICATION;
    }
}
