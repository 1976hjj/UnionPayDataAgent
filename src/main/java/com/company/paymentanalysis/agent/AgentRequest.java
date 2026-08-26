package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Stable, UI-neutral request contract for the unified Agent entry point. */
public record AgentRequest(
        @JsonAlias({"loginusername", "loginUsername"})
        String userId,
        String conversationId,
        String message,
        AgentEntryMode entryMode,
        String model,
        boolean confirmed,
        QueryContext queryContext,
        DimensionTemplate attributionTemplate,
        AttributionExecutionOptions attributionExecutionOptions,
        VisualizationOptions visualizationOptions,
        List<String> inputArtifactIds,
        Map<String, Object> parameters,
        AgentAction action) implements Serializable {

    public AgentRequest {
        entryMode = entryMode == null ? AgentEntryMode.BI_CHAT : entryMode;
        inputArtifactIds = inputArtifactIds == null ? List.of() : List.copyOf(inputArtifactIds);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        action = action == null ? AgentAction.MESSAGE : action;
    }

    public AgentRequest(
            String userId, String conversationId, String message, AgentEntryMode entryMode,
            String model, boolean confirmed, QueryContext queryContext,
            DimensionTemplate attributionTemplate,
            AttributionExecutionOptions attributionExecutionOptions,
            VisualizationOptions visualizationOptions, AgentAction action) {
        this(userId, conversationId, message, entryMode, model, confirmed, queryContext,
                attributionTemplate, attributionExecutionOptions, visualizationOptions,
                List.of(), Map.of(), action);
    }

    public AgentRequest withVisualizationOptions(VisualizationOptions options) {
        return new AgentRequest(
                userId, conversationId, message, AgentEntryMode.VISUALIZATION, model, confirmed,
                queryContext, attributionTemplate, attributionExecutionOptions, options,
                inputArtifactIds, parameters, action);
    }
}
