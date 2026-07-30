package com.company.paymentanalysis.execution;

import java.io.Serializable;

public record QueryExecutionRecord(
        String queryId,
        String smartBiRequestJson,
        Object rawResponse,
        long executionTimeMillis,
        QueryExecutionStatus status,
        String errorMessage) implements Serializable {

    public QueryExecutionRecord {
        queryId = queryId == null ? "" : queryId;
        smartBiRequestJson = smartBiRequestJson == null ? "" : smartBiRequestJson;
        if (executionTimeMillis < 0) {
            throw new IllegalArgumentException("executionTimeMillis 不能小于 0");
        }
        if (status == null) {
            throw new IllegalArgumentException("查询执行状态不能为空");
        }
        errorMessage = errorMessage == null ? "" : errorMessage;
    }
}
