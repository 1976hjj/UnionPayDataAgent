package com.company.paymentanalysis.semantic;

import com.company.paymentanalysis.semantic.BusinessSemanticRule.TargetFilter;
import java.io.Serializable;
import java.util.List;

public interface BusinessSemanticProcessor {

    NormalizationResult normalize(String originalMessage, QuerySemanticIntent intent);

    static BusinessSemanticProcessor noOp() {
        return (message, intent) -> NormalizationResult.unchanged(intent);
    }

    record NormalizationResult(
            QuerySemanticIntent intent,
            List<String> enforcedMetricIds,
            List<TargetFilter> enforcedFilters,
            List<String> consumedTerms,
            List<AppliedSemanticRule> appliedRules) {
        public NormalizationResult {
            intent = intent == null ? QuerySemanticIntent.empty() : intent;
            enforcedMetricIds = enforcedMetricIds == null ? List.of() : List.copyOf(enforcedMetricIds);
            enforcedFilters = enforcedFilters == null ? List.of() : List.copyOf(enforcedFilters);
            consumedTerms = consumedTerms == null ? List.of() : List.copyOf(consumedTerms);
            appliedRules = appliedRules == null ? List.of() : List.copyOf(appliedRules);
        }

        public static NormalizationResult unchanged(QuerySemanticIntent intent) {
            return new NormalizationResult(intent, List.of(), List.of(), List.of(), List.of());
        }
    }

    record AppliedSemanticRule(
            String knowledgeId,
            String term,
            String description,
            String confirmationDisplay,
            boolean requiresConfirmation,
            List<String> metricTerms,
            List<QuerySemanticIntent.FilterTerm> filterTerms) implements Serializable {
        public AppliedSemanticRule {
            metricTerms = metricTerms == null ? List.of() : List.copyOf(metricTerms);
            filterTerms = filterTerms == null ? List.of() : List.copyOf(filterTerms);
        }
    }
}
