package com.company.paymentanalysis.agent.tool;

import java.util.List;
import java.util.Map;

public record ToolExecutionResult(
        String status,
        List<String> outputArtifactIds,
        Map<String, Object> details) {

    public ToolExecutionResult {
        outputArtifactIds = outputArtifactIds == null ? List.of() : List.copyOf(outputArtifactIds);
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
