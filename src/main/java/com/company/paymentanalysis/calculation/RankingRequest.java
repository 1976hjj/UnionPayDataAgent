package com.company.paymentanalysis.calculation;

import java.io.Serializable;

public record RankingRequest(
        String metricField,
        SortDirection direction,
        Integer limit,
        NullValuePolicy nullValuePolicy) implements Serializable {

    public RankingRequest {
        if (metricField == null || metricField.isBlank()) {
            throw new IllegalArgumentException("排名指标不能为空");
        }
        direction = direction == null ? SortDirection.DESC : direction;
        limit = limit == null ? 10 : limit;
        if (limit <= 0) {
            throw new IllegalArgumentException("排名 limit 必须大于 0");
        }
        if (limit > 100) {
            throw new IllegalArgumentException("排名 limit 不能超过 100");
        }
        nullValuePolicy = nullValuePolicy == null ? NullValuePolicy.EXCLUDE : nullValuePolicy;
    }
}
