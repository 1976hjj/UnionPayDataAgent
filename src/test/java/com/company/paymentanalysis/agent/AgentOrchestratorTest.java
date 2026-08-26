package com.company.paymentanalysis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.agent.planner.AgentPlan;
import com.company.paymentanalysis.agent.planner.AgentPlanExecutor;
import com.company.paymentanalysis.agent.planner.SupervisorPlanner;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService.ActiveSkill;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentOrchestratorTest {

    @Test
    void unfinishedQueryOwnsTheTurnAndBypassesPlanner() {
        SupervisorPlanner planner = mock(SupervisorPlanner.class);
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        when(memory.activeSkill("user", "conversation")).thenReturn(Optional.of(ActiveSkill.QUERY));
        when(executor.execute(any(), any(), any())).thenReturn(response("QUERY"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(planner, executor, memory);

        AgentResponse response = orchestrator.respond(request("dp作为收单机构名称"));

        ArgumentCaptor<AgentPlan> plan = ArgumentCaptor.forClass(AgentPlan.class);
        verify(executor).execute(plan.capture(), any(), any());
        verify(planner, never()).plan(any(), any());
        assertThat(plan.getValue().plannerId()).isEqualTo("active-skill-owner");
        assertThat(plan.getValue().steps()).singleElement()
                .extracting(AgentPlan.PlanStep::capabilityId).isEqualTo("query");
        assertThat(response.activeSkill()).isEqualTo("QUERY");
    }

    @Test
    void explicitCancellationReleasesOwnershipWithoutExecutingTheSkill() {
        SupervisorPlanner planner = mock(SupervisorPlanner.class);
        AgentPlanExecutor executor = mock(AgentPlanExecutor.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        when(memory.activeSkill("user", "conversation")).thenReturn(Optional.of(ActiveSkill.QUERY));
        AgentOrchestrator orchestrator = new AgentOrchestrator(planner, executor, memory);

        AgentResponse response = orchestrator.respond(request("不查了"));

        verify(memory).cancelActiveSkill("user", "conversation", ActiveSkill.QUERY, "不查了");
        verify(planner, never()).plan(any(), any());
        verify(executor, never()).execute(any(), any(), any());
        assertThat(response.status()).isEqualTo("cancelled");
    }

    private AgentRequest request(String message) {
        return new AgentRequest(
                "user", "conversation", message, AgentEntryMode.BI_CHAT, "model", false,
                null, null, null, null, AgentAction.MESSAGE);
    }

    private AgentResponse response(String skill) {
        return new AgentResponse(
                "confirming", skill, "reply", "conversation",
                new AgentViewModel("query", null), "query", List.of());
    }
}
