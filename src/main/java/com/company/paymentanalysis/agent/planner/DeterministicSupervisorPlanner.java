package com.company.paymentanalysis.agent.planner;

import com.company.paymentanalysis.agent.AgentContext;
import com.company.paymentanalysis.agent.AgentEntryMode;
import com.company.paymentanalysis.agent.AgentRequest;
import com.company.paymentanalysis.agent.SkillRegistry;
import com.company.paymentanalysis.agent.VisualizationOptions;
import com.company.paymentanalysis.agent.tool.ToolRegistry;
import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload.ChartType;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService.ArtifactNotFoundException;
import com.company.paymentanalysis.agent.planner.AgentPlan.CapabilityType;
import com.company.paymentanalysis.agent.planner.AgentPlan.PlanStep;
import com.company.paymentanalysis.agent.planner.AgentPlan.Status;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Conservative first supervisor. Its contract can later be implemented by an
 * LLM planner without changing skills, tools or the orchestrator.
 */
@Service
public class DeterministicSupervisorPlanner implements SupervisorPlanner {

    static final String PLANNER_ID = "deterministic-supervisor";

    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private final ArtifactAccessService artifactAccessService;

    public DeterministicSupervisorPlanner(
            SkillRegistry skillRegistry,
            ToolRegistry toolRegistry,
            ArtifactAccessService artifactAccessService) {
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.artifactAccessService = artifactAccessService;
    }

    @Override
    public AgentPlan plan(AgentRequest request, AgentContext context) {
        if (request == null || context == null) {
            throw new IllegalArgumentException("Planner 输入不能为空");
        }
        if (context.entryMode() == AgentEntryMode.ATTRIBUTION) {
            return skillPlan(request.message(), "attribution", List.of(), Map.of());
        }

        String message = normalized(request.message());
        boolean visualizationIntent = context.entryMode() == AgentEntryMode.VISUALIZATION
                || containsAny(message, "图表", "折线图", "柱状图", "饼图", "可视化");
        boolean exportIntent = containsAny(message, "导出", "下载", "excel", "xlsx", "csv");

        if (exportIntent) {
            Optional<String> source = querySource(request, context);
            if (source.isEmpty()) {
                return needsInput(request.message(), "当前会话没有可导出的查询结果，请先完成查询。");
            }
            toolRegistry.require("export-data");
            String format = message.contains("csv") ? "CSV" : "XLSX";
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("format", format);
            Object requestedFileName = request.parameters().get("fileName");
            if (requestedFileName != null) arguments.put("fileName", requestedFileName);
            return toolPlan(request.message(), "export-data", List.of(source.get()), arguments);
        }

        if (visualizationIntent) {
            Optional<String> source = visualizationSource(request, context);
            if (source.isEmpty()) {
                return needsInput(request.message(), "当前会话没有可绘图的查询结果，请先完成带维度的查询。");
            }
            skillRegistry.require("visualization");
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("sourceArtifactId", source.get());
            arguments.put("chartType", chartType(message, request.visualizationOptions()).name());
            copyVisualizationArguments(request, arguments);
            return skillPlan(request.message(), "visualization", List.of(source.get()), arguments);
        }

        skillRegistry.require("query");
        return skillPlan(request.message(), "query", List.of(), Map.of());
    }

    private Optional<String> visualizationSource(AgentRequest request, AgentContext context) {
        if (request.visualizationOptions() != null
                && !blank(request.visualizationOptions().sourceArtifactId())) {
            return ownedQuery(request.userId(), request.visualizationOptions().sourceArtifactId());
        }
        return querySource(request, context);
    }

    private Optional<String> querySource(AgentRequest request, AgentContext context) {
        for (String artifactId : request.inputArtifactIds()) {
            Optional<String> owned = ownedQuery(context.userId(), artifactId);
            if (owned.isPresent()) return owned;
        }
        return artifactAccessService.findByConversation(context.userId(), context.conversationId()).stream()
                .filter(view -> view.artifactType() == ArtifactType.QUERY_RESULT)
                .map(ArtifactAccessService.ArtifactView::artifactId)
                .findFirst();
    }

    private Optional<String> ownedQuery(String userId, String artifactId) {
        try {
            Artifact artifact = artifactAccessService.requireOwnedArtifact(userId, artifactId);
            return artifact.artifactType() == ArtifactType.QUERY_RESULT
                    ? Optional.of(artifact.artifactId()) : Optional.empty();
        } catch (ArtifactNotFoundException ignored) {
            return Optional.empty();
        }
    }

    private void copyVisualizationArguments(AgentRequest request, Map<String, Object> arguments) {
        VisualizationOptions options = request.visualizationOptions();
        if (options == null) return;
        if (!blank(options.dimensionId())) arguments.put("dimensionId", options.dimensionId());
        if (!options.metricIds().isEmpty()) arguments.put("metricIds", options.metricIds());
        if (!blank(options.title())) arguments.put("title", options.title());
    }

    private ChartType chartType(String message, VisualizationOptions options) {
        if (options != null && options.chartType() != null && options.chartType() != ChartType.AUTO) {
            return options.chartType();
        }
        if (message.contains("饼图")) return ChartType.PIE;
        if (message.contains("柱状图")) return ChartType.BAR;
        if (message.contains("折线图")) return ChartType.LINE;
        return ChartType.AUTO;
    }

    private AgentPlan skillPlan(String goal, String skillId, List<String> inputs, Map<String, Object> arguments) {
        skillRegistry.require(skillId);
        return ready(goal, new PlanStep("step-1", CapabilityType.SKILL, skillId, inputs, arguments, List.of()));
    }

    private AgentPlan toolPlan(String goal, String toolId, List<String> inputs, Map<String, Object> arguments) {
        toolRegistry.require(toolId);
        return ready(goal, new PlanStep("step-1", CapabilityType.TOOL, toolId, inputs, arguments, List.of()));
    }

    private AgentPlan ready(String goal, PlanStep step) {
        return plan(goal, Status.READY, "计划已生成", List.of(step));
    }

    private AgentPlan needsInput(String goal, String message) {
        return plan(goal, Status.NEEDS_INPUT, message, List.of());
    }

    private AgentPlan plan(String goal, Status status, String message, List<PlanStep> steps) {
        return new AgentPlan(
                "plan_" + UUID.randomUUID().toString().replace("-", ""), 1, PLANNER_ID,
                goal == null ? "" : goal.trim(), status, message, steps, Instant.now());
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
