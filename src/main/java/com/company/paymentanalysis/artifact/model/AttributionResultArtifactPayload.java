package com.company.paymentanalysis.artifact.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/** Stable attribution summary plus the versioned full analysis result. */
public record AttributionResultArtifactPayload(
        String metricId,
        String metricName,
        String currentPeriod,
        String comparisonPeriod,
        Overall overall,
        String summary,
        List<String> findings,
        List<String> recommendations,
        AttributionContract attributionContract,
        JsonNode analysisResult) {

    public AttributionResultArtifactPayload {
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }

    public record Overall(
            BigDecimal currentValue,
            BigDecimal comparisonValue,
            BigDecimal changeAmount,
            BigDecimal changeRate,
            String direction) {
    }

    public record AttributionContract(
            List<Filter> filters,
            int maxDepth,
            int maxQueries,
            int topN,
            int maxBranches) {

        public AttributionContract {
            filters = filters == null ? List.of() : List.copyOf(filters);
        }
    }

    public record Filter(String dimensionId, String operator, List<String> values) {
        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
