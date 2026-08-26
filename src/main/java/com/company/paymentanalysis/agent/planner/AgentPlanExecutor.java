package com.company.paymentanalysis.agent.planner;

import com.company.paymentanalysis.agent.AgentContext;
import com.company.paymentanalysis.agent.AgentRequest;
import com.company.paymentanalysis.agent.AgentResponse;
import com.company.paymentanalysis.agent.AgentSkill;
import com.company.paymentanalysis.agent.AgentViewModel;
import com.company.paymentanalysis.agent.SkillRegistry;
import com.company.paymentanalysis.agent.VisualizationOptions;
import com.company.paymentanalysis.agent.planner.AgentPlan.CapabilityType;
import com.company.paymentanalysis.agent.planner.AgentPlan.PlanStep;
import com.company.paymentanalysis.agent.planner.AgentPlan.Status;
import com.company.paymentanalysis.agent.tool.ToolExecutionRequest;
import com.company.paymentanalysis.agent.tool.ToolExecutionResult;
import com.company.paymentanalysis.agent.tool.ToolRegistry;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload.ChartType;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Executes a validated supervisor plan while keeping capability lookup centralized. */
@Service
public class AgentPlanExecutor {

    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;

    public AgentPlanExecutor(SkillRegistry skillRegistry, ToolRegistry toolRegistry) {
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
    }

    public AgentResponse execute(AgentPlan plan, AgentRequest request, AgentContext context) {
        if (plan.status() == Status.NEEDS_INPUT) {
            return new AgentResponse(
                    "clarifying", "SUPERVISOR", plan.message(), context.conversationId(),
                    new AgentViewModel("planner-clarification", plan), null, List.of(), plan);
        }
        if (plan.steps().size() != 1) {
            throw new IllegalStateException("当前执行器仅支持单步计划");
        }
        PlanStep step = plan.steps().get(0);
        AgentResponse response = step.capabilityType() == CapabilityType.SKILL
                ? executeSkill(step, request, context)
                : executeTool(step, context);
        return response.withPlan(plan);
    }

    private AgentResponse executeSkill(PlanStep step, AgentRequest request, AgentContext context) {
        AgentSkill skill = skillRegistry.require(step.capabilityId());
        AgentRequest effectiveRequest = "visualization".equals(step.capabilityId())
                ? request.withVisualizationOptions(visualizationOptions(step)) : request;
        return skill.execute(effectiveRequest, context);
    }

    private AgentResponse executeTool(PlanStep step, AgentContext context) {
        ToolExecutionResult result = toolRegistry.require(step.capabilityId()).execute(new ToolExecutionRequest(
                context.userId(), context.conversationId(), step.inputArtifactIds(), step.arguments()));
        return new AgentResponse(
                result.status(), "TOOL", "导出文件已生成", context.conversationId(),
                new AgentViewModel("file", new ToolViewPayload(step.capabilityId(), result.details())),
                null, result.outputArtifactIds());
    }

    private VisualizationOptions visualizationOptions(PlanStep step) {
        Map<String, Object> arguments = step.arguments();
        String sourceArtifactId = string(arguments.get("sourceArtifactId"));
        ChartType chartType = ChartType.valueOf(string(arguments.get("chartType")));
        String dimensionId = nullableString(arguments.get("dimensionId"));
        String title = nullableString(arguments.get("title"));
        List<String> metricIds = stringList(arguments.get("metricIds"));
        return new VisualizationOptions(sourceArtifactId, chartType, dimensionId, metricIds, title);
    }

    private String string(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("计划参数缺失");
        }
        return value.toString();
    }

    private String nullableString(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(this::string).toList();
    }

    public record ToolViewPayload(String toolId, Map<String, Object> details) {
        public ToolViewPayload {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
