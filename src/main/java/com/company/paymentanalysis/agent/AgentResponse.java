package com.company.paymentanalysis.agent;

import java.io.Serializable;

/** Common response envelope; payload remains owned by the selected skill. */
public record AgentResponse(
        String status,
        String activeSkill,
        String reply,
        String conversationId,
        AgentViewModel viewModel) implements Serializable {
}
