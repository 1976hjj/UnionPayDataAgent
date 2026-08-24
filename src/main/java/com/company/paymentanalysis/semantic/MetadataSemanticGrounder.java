package com.company.paymentanalysis.semantic;

import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.MetadataCandidate;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.company.paymentanalysis.ragflow.ValueCandidateMatcher;
import com.company.paymentanalysis.semantic.QuerySemanticIntent.FilterTerm;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Converts strict value candidates into deterministic slot-grounding decisions. */
public final class MetadataSemanticGrounder {

    private static final Set<String> TIME_DIMENSIONS = Set.of(
            "sett_dt_Year2", "sett_dt_Month2", "sett_dt_Day2");

    private MetadataSemanticGrounder() {
    }

    public static GroundingResult ground(QuerySemanticIntent intent, RetrievedMetadata metadata) {
        if (metadata == null || metadata.values().isEmpty()) {
            return GroundingResult.empty();
        }
        QuerySemanticIntent semanticIntent = intent == null ? QuerySemanticIntent.empty() : intent;
        List<GroundedFilter> required = new ArrayList<>();
        List<AmbiguousGrounding> ambiguous = new ArrayList<>();
        Set<GroundingKey> explicitlyAssociatedKeys = new LinkedHashSet<>();
        groundExplicitFilterTerms(
                semanticIntent, metadata, required, ambiguous, explicitlyAssociatedKeys);

        Map<GroundingKey, List<MetadataCandidate>> groups = new LinkedHashMap<>();
        for (MetadataCandidate candidate : metadata.values()) {
            if (candidate == null
                    || TIME_DIMENSIONS.contains(candidate.fieldId())
                    || !StringUtils.hasText(candidate.queryTerm())
                    || !StringUtils.hasText(candidate.value())
                    || !ValueCandidateMatcher.matches(
                            candidate.queryTerm(), candidate.value(), candidate.description())) {
                continue;
            }
            GroundingKey key = new GroundingKey(
                    normalize(candidate.queryTerm()), normalize(candidate.value()));
            if (explicitlyAssociatedKeys.contains(key)) {
                continue;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }

        for (Map.Entry<GroundingKey, List<MetadataCandidate>> entry : groups.entrySet()) {
            MetadataCandidate representative = entry.getValue().get(0);
            String sourceTerm = representative.queryTerm().trim();
            String matchedValue = representative.value().trim();
            Map<String, MetadataCandidate> fields = new LinkedHashMap<>();
            for (MetadataCandidate candidate : entry.getValue()) {
                fields.merge(candidate.fieldId(), candidate,
                        (left, right) -> left.score() >= right.score() ? left : right);
            }
            if (fields.size() == 1) {
                MetadataCandidate candidate = fields.values().iterator().next();
                required.add(new GroundedFilter(
                        sourceTerm, matchedValue, candidate.fieldId(),
                        candidate.fieldName(), candidate.score()));
            } else if (fields.size() > 1) {
                ambiguous.add(new AmbiguousGrounding(
                        sourceTerm, matchedValue,
                        fields.values().stream()
                                .sorted(Comparator.comparing(MetadataCandidate::fieldId))
                                .map(candidate -> new GroundingCandidate(
                                        candidate.fieldId(), candidate.fieldName(), candidate.score()))
                                .toList()));
            }
        }
        return new GroundingResult(required, ambiguous);
    }

    /**
     * Grounds an explicit filter as a field/value pair. Value retrieval alone is
     * intentionally broad (for example the value "1" may belong to many code
     * fields), so a unique field-name candidate is intersected with those value
     * owners before the filter is declared ambiguous.
     */
    private static void groundExplicitFilterTerms(
            QuerySemanticIntent intent,
            RetrievedMetadata metadata,
            List<GroundedFilter> required,
            List<AmbiguousGrounding> ambiguous,
            Set<GroundingKey> explicitlyAssociatedKeys) {
        Map<String, GroundedFilter> uniqueRequired = new LinkedHashMap<>();
        Map<String, AmbiguousGrounding> uniqueAmbiguous = new LinkedHashMap<>();
        for (FilterTerm filterTerm : intent.filterTerms()) {
            if (filterTerm == null || !StringUtils.hasText(filterTerm.dimensionTerm())) {
                continue;
            }
            for (String authoredValue : filterTerm.values()) {
                if (!StringUtils.hasText(authoredValue)) continue;
                List<MetadataCandidate> owners = metadata.values().stream()
                        .filter(candidate -> candidate != null
                                && !TIME_DIMENSIONS.contains(candidate.fieldId())
                                && StringUtils.hasText(candidate.value())
                                && ValueCandidateMatcher.matches(
                                        authoredValue, candidate.value(), candidate.description()))
                        .toList();
                if (owners.isEmpty()) continue;
                owners.forEach(candidate -> explicitlyAssociatedKeys.add(new GroundingKey(
                        normalize(candidate.queryTerm()), normalize(candidate.value()))));

                Map<String, MetadataCandidate> ownerFields = strongestByField(owners);
                Map<String, MetadataCandidate> fieldEvidence = new LinkedHashMap<>();
                for (MetadataCandidate owner : ownerFields.values()) {
                    if (hasDimensionEvidence(filterTerm.dimensionTerm(), owner, metadata.dimensions())) {
                        fieldEvidence.put(owner.fieldId(), owner);
                    }
                }
                Map<String, MetadataCandidate> candidates = fieldEvidence.size() == 1
                        ? fieldEvidence : ownerFields;
                if (candidates.size() == 1) {
                    MetadataCandidate candidate = candidates.values().iterator().next();
                    GroundedFilter grounded = new GroundedFilter(
                            filterTerm.dimensionTerm().trim(), candidate.value().trim(),
                            candidate.fieldId(), candidate.fieldName(), candidate.score());
                    uniqueRequired.put(candidate.fieldId() + "|" + normalize(candidate.value()), grounded);
                } else if (candidates.size() > 1) {
                    MetadataCandidate representative = candidates.values().iterator().next();
                    AmbiguousGrounding grounding = new AmbiguousGrounding(
                            authoredValue.trim(), representative.value().trim(),
                            candidates.values().stream()
                                    .sorted(Comparator.comparing(MetadataCandidate::fieldId))
                                    .map(candidate -> new GroundingCandidate(
                                            candidate.fieldId(), candidate.fieldName(), candidate.score()))
                                    .toList());
                    uniqueAmbiguous.put(
                            normalize(authoredValue) + "|" + normalize(representative.value()), grounding);
                }
            }
        }
        required.addAll(uniqueRequired.values());
        ambiguous.addAll(uniqueAmbiguous.values());
    }

    private static Map<String, MetadataCandidate> strongestByField(List<MetadataCandidate> candidates) {
        Map<String, MetadataCandidate> fields = new LinkedHashMap<>();
        for (MetadataCandidate candidate : candidates) {
            fields.merge(candidate.fieldId(), candidate,
                    (left, right) -> left.score() >= right.score() ? left : right);
        }
        return fields;
    }

    private static boolean hasDimensionEvidence(
            String dimensionTerm,
            MetadataCandidate valueOwner,
            List<MetadataCandidate> dimensionCandidates) {
        String normalizedTerm = normalize(dimensionTerm);
        if (normalizedTerm.equals(normalize(valueOwner.fieldName()))
                || normalizedTerm.equals(normalize(valueOwner.fieldId()))) {
            return true;
        }
        return dimensionCandidates.stream().anyMatch(candidate ->
                candidate != null
                        && valueOwner.fieldId().equals(candidate.fieldId())
                        && (normalizedTerm.equals(normalize(candidate.fieldName()))
                                || normalizedTerm.equals(normalize(candidate.fieldId()))));
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record GroundingKey(String normalizedSourceTerm, String normalizedValue) {
    }

    public record GroundedFilter(
            String sourceTerm, String matchedValue, String dimensionId,
            String dimensionName, double score) {
    }

    public record GroundingCandidate(String dimensionId, String dimensionName, double score) {
    }

    public record AmbiguousGrounding(
            String sourceTerm, String matchedValue, List<GroundingCandidate> candidates) {
        public AmbiguousGrounding {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public Set<String> dimensionIds() {
            return candidates.stream().map(GroundingCandidate::dimensionId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public record GroundingResult(
            List<GroundedFilter> requiredFilters,
            List<AmbiguousGrounding> ambiguousFilters) {
        public GroundingResult {
            requiredFilters = requiredFilters == null ? List.of() : List.copyOf(requiredFilters);
            ambiguousFilters = ambiguousFilters == null ? List.of() : List.copyOf(ambiguousFilters);
        }

        public static GroundingResult empty() {
            return new GroundingResult(List.of(), List.of());
        }
    }
}
