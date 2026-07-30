package com.company.paymentanalysis.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QueryPlanBuilder {

    private final AnalysisCatalog catalog;

    public QueryPlanBuilder(AnalysisCatalog catalog) {
        this.catalog = catalog;
    }

    public QueryPlan build(IntentRecognitionResult recognition, AnalysisContext context) {
        if (recognition == null) {
            throw new IllegalArgumentException("IntentRecognitionResult 不能为空");
        }
        AnalysisContext safeContext = context == null
                ? new AnalysisContext(null, "", List.of(), List.of())
                : context;

        String recognizedMetric = catalog.resolveMetric(recognition.metricText());
        String metricCode = "rmbAmount".equals(recognizedMetric)
                ? recognizedMetric
                : safeContext.metricCode().isBlank() ? recognizedMetric : safeContext.metricCode();

        LinkedHashSet<String> dimensions = new LinkedHashSet<>();
        if (!safeContext.dimensionCodes().isEmpty()) {
            dimensions.addAll(safeContext.dimensionCodes());
        } else {
            for (String text : recognition.dimensionTexts()) {
                String code = catalog.resolveDimension(text);
                if (!code.isBlank()) {
                    dimensions.add(code);
                }
            }
        }

        List<FilterCondition> filters = recognition.filters().isEmpty()
                ? normalizeFilters(safeContext.filters())
                : normalizeFilters(recognition.filters());
        List<ComparisonSubject> subjects = normalizeSubjects(recognition.comparisonSubjects());
        if (recognition.intent() == IntentType.COMPARE_QUERY && subjectsHaveTimeFilters(subjects)) {
            filters = filters.stream()
                    .filter(filter -> !"tradeDate".equals(filter.field()))
                    .toList();
        }

        return new QueryPlan(
                recognition.intent(),
                recognition.confidence(),
                metricCode,
                List.copyOf(dimensions),
                filters,
                subjects,
                recognition.requestedCalculations(),
                recognition.topN(),
                recognition.needsDataQuery(),
                recognition.needsKnowledgeBase(),
                recognition.missingSlots(),
                recognition.clarificationQuestion());
    }

    private List<ComparisonSubject> normalizeSubjects(List<ComparisonSubject> subjects) {
        List<ComparisonSubject> result = new ArrayList<>();
        for (ComparisonSubject subject : subjects) {
            result.add(new ComparisonSubject(subject.label(), normalizeFilters(subject.filters())));
        }
        return List.copyOf(result);
    }

    private List<FilterCondition> normalizeFilters(List<FilterCondition> filters) {
        return filters.stream()
                .map(filter -> new FilterCondition(
                        catalog.resolveFilterField(filter.field()),
                        filter.operator().toUpperCase(),
                        filter.values()))
                .toList();
    }

    private boolean subjectsHaveTimeFilters(List<ComparisonSubject> subjects) {
        return subjects.stream()
                .flatMap(subject -> subject.filters().stream())
                .anyMatch(filter -> "tradeDate".equals(filter.field()));
    }
}
