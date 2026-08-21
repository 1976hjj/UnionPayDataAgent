package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.Serializable;

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
        AgentAction action) implements Serializable {

    public AgentRequest {
        entryMode = entryMode == null ? AgentEntryMode.BI_CHAT : entryMode;
        action = action == null ? AgentAction.MESSAGE : action;
    }
}
