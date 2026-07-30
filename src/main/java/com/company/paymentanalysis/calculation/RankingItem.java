package com.company.paymentanalysis.calculation;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

public record RankingItem(
        int rank,
        Map<String, Object> dimensionValues,
        BigDecimal metricValue) implements Serializable {

    public RankingItem {
        dimensionValues = dimensionValues == null ? Map.of() : Map.copyOf(dimensionValues);
    }
}
