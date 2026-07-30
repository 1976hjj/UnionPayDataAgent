package com.company.paymentanalysis.normalize;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record NormalizedDataRow(
        Map<String, Object> dimensions,
        Map<String, BigDecimal> metrics,
        int originalIndex) implements Serializable {

    public NormalizedDataRow {
        dimensions = dimensions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        metrics = metrics == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        if (originalIndex < 0) {
            throw new IllegalArgumentException("originalIndex 不能小于 0");
        }
    }
}
