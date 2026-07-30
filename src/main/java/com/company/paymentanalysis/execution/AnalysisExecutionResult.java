package com.company.paymentanalysis.execution;

import com.company.paymentanalysis.analysis.IntentType;
import java.io.Serializable;
import java.util.List;

public record AnalysisExecutionResult(
        String traceId,
        String planId,
        IntentType intent,
        ExecutionStatus status,
        List<QueryExecutionRecord> queryRecords,
        Object rawData,
        Object calculationResult,
        List<String> warnings,
        String responseText) implements Serializable {

    public AnalysisExecutionResult {
        traceId = traceId == null ? "" : traceId;
        planId = planId == null ? "" : planId;
        if (intent == null) {
            throw new IllegalArgumentException("执行意图不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("执行状态不能为空");
        }
        queryRecords = queryRecords == null ? List.of() : List.copyOf(queryRecords);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        responseText = responseText == null ? "" : responseText;
    }
}
