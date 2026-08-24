package com.company.paymentanalysis.semantic;

import com.company.paymentanalysis.semantic.BusinessSemanticProcessor.AppliedSemanticRule;
import com.company.paymentanalysis.semantic.BusinessSemanticProcessor.NormalizationResult;
import com.company.paymentanalysis.semantic.BusinessSemanticRetrievalTool.RetrievalResult;
import com.company.paymentanalysis.semantic.BusinessSemanticRetrievalTool.RuleCandidate;
import com.company.paymentanalysis.semantic.BusinessSemanticRule.SemanticFilter;
import com.company.paymentanalysis.semantic.BusinessSemanticRule.TargetFilter;
import com.company.paymentanalysis.semantic.QuerySemanticIntent.FilterTerm;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Applies retrieved rules deterministically; the LLM never invents bundle expansion. */
@Component
public class BusinessSemanticNormalizer {

    public static final String RULE_GENERATED_CONTEXT = "业务语义规则标准化";

    public NormalizationResult normalize(QuerySemanticIntent source, RetrievalResult retrieval) {
        QuerySemanticIntent intent = source == null ? QuerySemanticIntent.empty() : source;
        if (retrieval == null || retrieval.candidates().isEmpty()) {
            return NormalizationResult.unchanged(intent);
        }
        List<String> metrics = new ArrayList<>(intent.metricTerms());
        List<String> groups = new ArrayList<>(intent.groupTerms());
        List<FilterTerm> filters = new ArrayList<>(intent.filterTerms());
        List<QuerySemanticIntent.SortTerm> sorts = new ArrayList<>(intent.sortTerms());
        List<String> unmapped = new ArrayList<>(intent.unmappedTerms());
        LinkedHashSet<String> enforcedMetrics = new LinkedHashSet<>();
        List<TargetFilter> enforcedFilters = new ArrayList<>();
        LinkedHashSet<String> consumedTerms = new LinkedHashSet<>();
        List<AppliedSemanticRule> applied = new ArrayList<>();

        for (var candidate : retrieval.candidates()) {
            BusinessSemanticRule rule = candidate.rule();
            if (rule == null || !rule.enabled() || !isDirectlyMentioned(candidate)) continue;
            List<String> phrases = rule.matchPhrases();
            removeMatching(metrics, phrases);
            removeMatching(groups, phrases);
            removeMatching(unmapped, phrases);
            consumedTerms.addAll(removeCoveredUnmappedFragments(unmapped, phrases));
            filters.removeIf(filter -> matches(filter, phrases));
            sorts.removeIf(sort -> sort != null && mentions(sort.fieldTerm(), phrases));
            consumedTerms.addAll(phrases);

            rule.semanticRewrite().metricTerms().forEach(term -> add(metrics, term));
            List<FilterTerm> generatedFilters = rule.semanticRewrite().filterTerms().stream()
                    .map(this::toFilterTerm).toList();
            generatedFilters.forEach(filter -> addFilter(filters, filter));
            enforcedMetrics.addAll(rule.targetConstraint().metricIds());
            rule.targetConstraint().dimensionFilters().forEach(filter -> addTargetFilter(enforcedFilters, filter));
            applied.add(new AppliedSemanticRule(
                    rule.knowledgeId(), rule.term(), rule.description(), rule.confirmationDisplay(),
                    rule.requiresConfirmation(), rule.semanticRewrite().metricTerms(), generatedFilters));
        }

        QuerySemanticIntent normalized = new QuerySemanticIntent(
                intent.searchTerms(), List.copyOf(metrics), List.copyOf(groups), List.copyOf(filters),
                List.copyOf(sorts), List.copyOf(unmapped));
        return new NormalizationResult(
                normalized, List.copyOf(enforcedMetrics), List.copyOf(enforcedFilters),
                List.copyOf(consumedTerms), List.copyOf(applied));
    }

    /**
     * Retrieval may be fuzzy, but a rule can only rewrite the query when one of
     * its complete terms or aliases appears in the user-authored retrieval text.
     * The reverse direction (for example, rule alias "消费金额" containing the
     * generic query term "金额") is candidate evidence only and never activates
     * a business rule.
     */
    private boolean isDirectlyMentioned(RuleCandidate candidate) {
        List<String> phrases = candidate.rule().matchPhrases();
        return candidate.matchedTerms().stream()
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .anyMatch(query -> phrases.stream()
                        .filter(StringUtils::hasText)
                        .map(this::normalize)
                        .anyMatch(phrase -> query.equals(phrase) || query.contains(phrase)));
    }

    private FilterTerm toFilterTerm(SemanticFilter filter) {
        return new FilterTerm(
                filter.dimensionTerm(), filter.operator(), filter.values(), RULE_GENERATED_CONTEXT);
    }

    private List<String> removeCoveredUnmappedFragments(List<String> values, List<String> phrases) {
        List<String> covered = values.stream().filter(value -> {
            String normalizedValue = normalize(value);
            return !normalizedValue.isBlank() && phrases.stream()
                    .filter(StringUtils::hasText)
                    .map(this::normalize)
                    .anyMatch(phrase -> phrase.contains(normalizedValue));
        }).toList();
        values.removeAll(covered);
        return covered;
    }

    private void removeMatching(List<String> values, List<String> phrases) {
        values.removeIf(value -> mentions(value, phrases));
    }

    private boolean matches(FilterTerm filter, List<String> phrases) {
        if (filter == null) return false;
        // Context commonly contains the whole utterance, so using it as a deletion key
        // would remove unrelated time/market filters that happen to share the sentence.
        if (mentions(filter.dimensionTerm(), phrases)) return true;
        return filter.values().stream().anyMatch(value -> mentions(value, phrases));
    }

    private boolean mentions(String value, List<String> phrases) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return false;
        return phrases.stream().filter(StringUtils::hasText).map(this::normalize)
                .anyMatch(phrase -> normalized.equals(phrase) || normalized.contains(phrase));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。；;：:（）()\\[\\]{}\"']+", "");
    }

    private void add(List<String> target, String value) {
        if (StringUtils.hasText(value) && !target.contains(value)) target.add(value);
    }

    private void addFilter(List<FilterTerm> target, FilterTerm filter) {
        if (filter != null && !target.contains(filter)) target.add(filter);
    }

    private void addTargetFilter(List<TargetFilter> target, TargetFilter filter) {
        if (filter == null) return;
        target.removeIf(existing -> existing.dimensionId().equals(filter.dimensionId()));
        target.add(filter);
    }
}
