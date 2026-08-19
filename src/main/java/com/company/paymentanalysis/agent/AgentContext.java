package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import java.io.Serializable;

/**
 * Server-side context supplied to a skill. Permission scope will be added here
 * later, so skills never need to read identity data from user messages.
 */
public record AgentContext(
        String userId,
        String conversationId,
        AgentEntryMode entryMode,
        String model,
        boolean confirmed,
        QueryContext queryContext,
        DimensionTemplate attributionTemplate,
        AttributionExecutionOptions attributionExecutionOptions,
        AgentAction action) implements Serializable {
}
