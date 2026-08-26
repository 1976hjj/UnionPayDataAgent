package com.company.paymentanalysis.agent.tool;

import java.util.List;
import java.util.Map;

public record ToolExecutionRequest(
        String userId,
        String conversationId,
        List<String> inputArtifactIds,
        Map<String, Object> arguments) {

    public ToolExecutionRequest {
        inputArtifactIds = inputArtifactIds == null ? List.of() : List.copyOf(inputArtifactIds);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
