package com.company.paymentanalysis.analysis;

import java.io.Serializable;
import java.util.List;

public record IntentRecognitionResult(
        IntentType intent,
        double confidence,
        String metricText,
        List<String> dimensionTexts,
        List<FilterCondition> filters,
        List<ComparisonSubject> comparisonSubjects,
        List<String> requestedCalculations,
        Integer topN,
        boolean needsDataQuery,
        boolean needsKnowledgeBase,
        List<String> missingSlots,
        String clarificationQuestion) implements Serializable {

    public IntentRecognitionResult {
        metricText = metricText == null ? "" : metricText.trim();
        dimensionTexts = dimensionTexts == null ? List.of() : List.copyOf(dimensionTexts);
        filters = filters == null ? List.of() : List.copyOf(filters);
        comparisonSubjects =
                comparisonSubjects == null ? List.of() : List.copyOf(comparisonSubjects);
        requestedCalculations =
                requestedCalculations == null ? List.of() : List.copyOf(requestedCalculations);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion.trim();
    }
}
