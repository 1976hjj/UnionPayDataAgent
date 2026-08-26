package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.agent.planner.AgentPlan;
import java.io.Serializable;
import java.util.List;

/** Common response envelope; payload remains owned by the selected skill. */
public record AgentResponse(
        String status,
        String activeSkill,
        String reply,
        String conversationId,
        AgentViewModel viewModel,
        String skillId,
        List<String> outputArtifactIds,
        AgentPlan plan) implements Serializable {

    public AgentResponse {
        outputArtifactIds = outputArtifactIds == null ? List.of() : List.copyOf(outputArtifactIds);
    }

    public AgentResponse(
            String status, String activeSkill, String reply,
            String conversationId, AgentViewModel viewModel) {
        this(status, activeSkill, reply, conversationId, viewModel, null, List.of(), null);
    }

    public AgentResponse(
            String status, String activeSkill, String reply, String conversationId,
            AgentViewModel viewModel, String skillId, List<String> outputArtifactIds) {
        this(status, activeSkill, reply, conversationId, viewModel, skillId, outputArtifactIds, null);
    }

    public AgentResponse withPlan(AgentPlan agentPlan) {
        return new AgentResponse(
                status, activeSkill, reply, conversationId, viewModel,
                skillId, outputArtifactIds, agentPlan);
    }
}
