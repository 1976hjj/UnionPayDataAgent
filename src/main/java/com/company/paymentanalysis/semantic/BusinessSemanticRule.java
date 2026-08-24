package com.company.paymentanalysis.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.List;

/** One independently maintainable chunk from the fourth business-semantics document. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record BusinessSemanticRule(
        String knowledgeId,
        String term,
        List<String> aliases,
        ConceptType conceptType,
        String description,
        MatchPolicy matchPolicy,
        SemanticRewrite semanticRewrite,
        TargetConstraint targetConstraint,
        boolean requiresConfirmation,
        String confirmationDisplay,
        Confidence confidence,
        List<String> sourceEvidence,
        boolean enabled,
        String version) implements Serializable {

    public BusinessSemanticRule {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        semanticRewrite = semanticRewrite == null ? SemanticRewrite.empty() : semanticRewrite;
        targetConstraint = targetConstraint == null ? TargetConstraint.empty() : targetConstraint;
        sourceEvidence = sourceEvidence == null ? List.of() : List.copyOf(sourceEvidence);
    }

    public List<String> matchPhrases() {
        java.util.ArrayList<String> phrases = new java.util.ArrayList<>();
        if (term != null && !term.isBlank()) phrases.add(term);
        aliases.stream().filter(value -> value != null && !value.isBlank())
                .filter(value -> !phrases.contains(value)).forEach(phrases::add);
        return List.copyOf(phrases);
    }

    public enum ConceptType { FILTER_RULE, FILTER_BUNDLE, METRIC_BUNDLE }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record MatchPolicy(String type, List<String> requiredContextTerms, boolean caseSensitive)
            implements Serializable {
        public MatchPolicy {
            requiredContextTerms = requiredContextTerms == null ? List.of() : List.copyOf(requiredContextTerms);
        }
    }

    public record SemanticRewrite(
            String relation, List<String> metricTerms, List<SemanticFilter> filterTerms)
            implements Serializable {
        public SemanticRewrite {
            metricTerms = metricTerms == null ? List.of() : List.copyOf(metricTerms);
            filterTerms = filterTerms == null ? List.of() : List.copyOf(filterTerms);
        }

        static SemanticRewrite empty() {
            return new SemanticRewrite("AND", List.of(), List.of());
        }
    }

    public record SemanticFilter(String dimensionTerm, String operator, List<String> values)
            implements Serializable {
        public SemanticFilter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record TargetConstraint(
            String relation, List<String> metricIds, List<TargetFilter> dimensionFilters)
            implements Serializable {
        public TargetConstraint {
            metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
            dimensionFilters = dimensionFilters == null ? List.of() : List.copyOf(dimensionFilters);
        }

        static TargetConstraint empty() {
            return new TargetConstraint("AND", List.of(), List.of());
        }
    }

    public record TargetFilter(String dimensionId, String operator, List<String> values)
            implements Serializable {
        public TargetFilter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
