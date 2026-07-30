package com.company.paymentanalysis.calculation;

import java.io.Serializable;

public record TrendRequest(
        String timeField,
        String metricField,
        TimeGranularity granularity,
        SortDirection sortDirection,
        Integer rateScale) implements Serializable {

    public TrendRequest {
        if (timeField == null || timeField.isBlank()) {
            throw new IllegalArgumentException("趋势时间字段不能为空");
        }
        if (metricField == null || metricField.isBlank()) {
            throw new IllegalArgumentException("趋势指标字段不能为空");
        }
        granularity = granularity == null ? TimeGranularity.MONTH : granularity;
        sortDirection = sortDirection == null ? SortDirection.ASC : sortDirection;
        rateScale = rateScale == null ? 4 : rateScale;
        if (rateScale < 0) {
            throw new IllegalArgumentException("rateScale 不能小于 0");
        }
    }

    public TrendRequest(
            String timeField,
            String metricField,
            TimeGranularity granularity,
            SortDirection sortDirection) {
        this(timeField, metricField, granularity, sortDirection, 4);
    }
}
