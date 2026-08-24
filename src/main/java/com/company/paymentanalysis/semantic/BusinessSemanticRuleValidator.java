package com.company.paymentanalysis.semantic;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.semantic.BusinessSemanticRule.TargetFilter;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Shared trust boundary for rules loaded from either local JSONL or remote retrieval. */
final class BusinessSemanticRuleValidator {

    private static final Set<String> ALLOWED_OPERATORS = Set.of(
            "EQUALS", "NOT_EQUALS", "IN", "BETWEEN",
            "GREATER", "GREATER_EQUALS", "LESS", "LESS_EQUALS");

    private BusinessSemanticRuleValidator() {
    }

    static void validate(BusinessSemanticRule rule) {
        if (rule == null || !StringUtils.hasText(rule.knowledgeId()) || !StringUtils.hasText(rule.term())
                || rule.conceptType() == null || rule.confidence() == null) {
            throw new IllegalArgumentException("missing required rule fields");
        }
        if (!"AND".equalsIgnoreCase(defaultRelation(rule.semanticRewrite().relation()))
                || !"AND".equalsIgnoreCase(defaultRelation(rule.targetConstraint().relation()))) {
            throw new IllegalArgumentException("current QueryState only supports AND rule bundles");
        }
        for (String metricId : rule.targetConstraint().metricIds()) {
            if (!QueryMetadataCatalog.isMetric(metricId)) {
                throw new IllegalArgumentException("unsupported metricId " + metricId);
            }
        }
        for (TargetFilter filter : rule.targetConstraint().dimensionFilters()) {
            if (filter == null || !QueryMetadataCatalog.isDimension(filter.dimensionId())
                    || !ALLOWED_OPERATORS.contains(filter.operator()) || filter.values().isEmpty()) {
                throw new IllegalArgumentException("unsupported target filter");
            }
            if ("BETWEEN".equals(filter.operator()) && filter.values().size() != 2) {
                throw new IllegalArgumentException("BETWEEN requires two values");
            }
        }
    }

    private static String defaultRelation(String relation) {
        return StringUtils.hasText(relation) ? relation : "AND";
    }
}
