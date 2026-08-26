package com.company.paymentanalysis.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.agent.AgentAction;
import com.company.paymentanalysis.agent.AgentContext;
import com.company.paymentanalysis.agent.AgentEntryMode;
import com.company.paymentanalysis.agent.AgentRequest;
import com.company.paymentanalysis.agent.AgentResponse;
import com.company.paymentanalysis.agent.AgentSkill;
import com.company.paymentanalysis.agent.AgentViewModel;
import com.company.paymentanalysis.agent.SkillRegistry;
import com.company.paymentanalysis.agent.planner.AgentPlan.CapabilityType;
import com.company.paymentanalysis.agent.planner.AgentPlan.PlanStep;
import com.company.paymentanalysis.agent.planner.AgentPlan.Status;
import com.company.paymentanalysis.agent.tool.AgentTool;
import com.company.paymentanalysis.agent.tool.ToolExecutionResult;
import com.company.paymentanalysis.agent.tool.ToolRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanExecutorTest {

    @Test
    void executesARegisteredSkillAndAttachesThePlanToItsResponse() {
        SkillRegistry skills = mock(SkillRegistry.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        AgentSkill query = mock(AgentSkill.class);
        AgentPlan plan = plan(Status.READY,
                List.of(new PlanStep("step-1", CapabilityType.SKILL, "query", List.of(), Map.of(), List.of())));
        when(skills.require("query")).thenReturn(query);
        when(query.execute(any(), any())).thenReturn(new AgentResponse(
                "completed", "QUERY", "ok", "conversation", new AgentViewModel("query", Map.of()),
                "query", List.of("art_query")));

        AgentResponse response = new AgentPlanExecutor(skills, tools).execute(plan, request(), context());

        assertThat(response.plan()).isSameAs(plan);
        assertThat(response.outputArtifactIds()).containsExactly("art_query");
    }

    @Test
    void executesAToolOrReturnsPlannerClarificationWithoutCallingCapabilities() {
        SkillRegistry skills = mock(SkillRegistry.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        AgentTool tool = mock(AgentTool.class);
        when(tools.require("export-data")).thenReturn(tool);
        when(tool.execute(any())).thenReturn(new ToolExecutionResult(
                "completed", List.of("art_file"), Map.of("format", "XLSX")));
        AgentPlanExecutor executor = new AgentPlanExecutor(skills, tools);
        AgentPlan ready = plan(Status.READY, List.of(new PlanStep(
                "step-1", CapabilityType.TOOL, "export-data", List.of("art_query"),
                Map.of("format", "XLSX"), List.of())));

        AgentResponse exported = executor.execute(ready, request(), context());
        AgentPlan blocked = plan(Status.NEEDS_INPUT, List.of());
        AgentResponse clarification = executor.execute(blocked, request(), context());

        assertThat(exported.outputArtifactIds()).containsExactly("art_file");
        assertThat(exported.viewModel().type()).isEqualTo("file");
        assertThat(clarification.status()).isEqualTo("clarifying");
        verify(skills, never()).require(any(String.class));
    }

    private AgentPlan plan(Status status, List<PlanStep> steps) {
        return new AgentPlan(
                "plan_1", 1, "test", "goal", status,
                status == Status.READY ? "ready" : "请先查询", steps, Instant.now());
    }

    private AgentRequest request() {
        return new AgentRequest(
                "user", "conversation", "message", AgentEntryMode.BI_CHAT, "model", false,
                null, null, null, null, List.of(), Map.of(), AgentAction.MESSAGE);
    }

    private AgentContext context() {
        return new AgentContext(
                "user", "conversation", AgentEntryMode.BI_CHAT, "model", false,
                null, null, null, AgentAction.MESSAGE);
    }
}
