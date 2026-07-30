package com.company.paymentanalysis.strategy.comparison;

import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.execution.QueryExecutionRecord;
import java.math.BigDecimal;
import java.util.List;

public record ComparisonRawResult(
        BigDecimal subjectAValue,
        BigDecimal subjectBValue,
        ExecutionStatus status,
        List<QueryExecutionRecord> queryRecords,
        Object rawData,
        List<String> warnings) {

    public ComparisonRawResult {
        queryRecords = queryRecords == null ? List.of() : List.copyOf(queryRecords);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
