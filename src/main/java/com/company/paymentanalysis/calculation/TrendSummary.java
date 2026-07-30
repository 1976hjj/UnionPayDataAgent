package com.company.paymentanalysis.calculation;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public record TrendSummary(
        List<TrendPoint> points,
        TrendPoint firstPoint,
        TrendPoint lastPoint,
        TrendPoint maxPoint,
        TrendPoint minPoint,
        BigDecimal totalDifference,
        BigDecimal totalChangeRate,
        TrendDirection direction,
        int validPointCount,
        List<String> warnings) implements Serializable {

    public TrendSummary {
        points = points == null ? List.of() : List.copyOf(points);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
