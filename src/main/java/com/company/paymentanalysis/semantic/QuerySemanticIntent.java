package com.company.paymentanalysis.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/** Field-ID-free semantic target produced by the first LLM pass. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record QuerySemanticIntent(
        List<SearchTerm> searchTerms,
        List<String> metricTerms,
        List<String> groupTerms,
        List<FilterTerm> filterTerms,
        List<SortTerm> sortTerms,
        List<String> unmappedTerms) implements Serializable {

    public QuerySemanticIntent {
        searchTerms = copy(searchTerms);
        metricTerms = copy(metricTerms);
        groupTerms = copy(groupTerms);
        filterTerms = copy(filterTerms);
        sortTerms = copy(sortTerms);
        unmappedTerms = copy(unmappedTerms);
    }

    public static QuerySemanticIntent empty() {
        return new QuerySemanticIntent(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Search terms include explicit extraction plus all unresolved slot text for multi-turn recovery. */
    public List<String> retrievalTerms() {
        List<String> result = new ArrayList<>();
        searchTerms.forEach(term -> add(result, term == null ? null : term.text()));
        metricTerms.forEach(term -> add(result, term));
        groupTerms.forEach(term -> add(result, term));
        filterTerms.forEach(filter -> {
            if (filter == null) return;
            add(result, filter.dimensionTerm());
            add(result, filter.context());
            filter.values().forEach(value -> add(result, value));
        });
        sortTerms.forEach(sort -> add(result, sort == null ? null : sort.fieldTerm()));
        unmappedTerms.forEach(term -> add(result, term));
        return List.copyOf(result);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void add(List<String> target, String value) {
        if (StringUtils.hasText(value) && !target.contains(value.trim())) {
            target.add(value.trim());
        }
    }

    public record SearchTerm(String text, String context) implements Serializable {
    }

    public record FilterTerm(
            String dimensionTerm, String operator, List<String> values, String context)
            implements Serializable {
        public FilterTerm {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record SortTerm(String fieldTerm, String direction) implements Serializable {
    }
}
