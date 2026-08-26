package com.company.paymentanalysis.artifact.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A renderer-neutral chart specification derived from a query result. */
public record ChartArtifactPayload(
        String sourceArtifactId,
        ChartType chartType,
        String dimensionId,
        String dimensionDisplayName,
        List<String> categories,
        List<Series> series) {

    public ChartArtifactPayload {
        categories = immutableAllowingNull(categories);
        series = series == null ? List.of() : List.copyOf(series);
    }

    public enum ChartType { AUTO, LINE, BAR, PIE }

    public record Series(
            String metricId,
            String displayName,
            String unit,
            List<BigDecimal> values) {

        public Series {
            values = immutableAllowingNull(values);
        }
    }

    private static <T> List<T> immutableAllowingNull(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
