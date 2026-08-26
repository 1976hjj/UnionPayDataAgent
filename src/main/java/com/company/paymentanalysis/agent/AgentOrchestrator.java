package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.agent.planner.AgentPlan;
import com.company.paymentanalysis.agent.planner.AgentPlanExecutor;
import com.company.paymentanalysis.agent.planner.SupervisorPlanner;
import com.company.paymentanalysis.agent.planner.AgentPlan.CapabilityType;
import com.company.paymentanalysis.agent.planner.AgentPlan.PlanStep;
import com.company.paymentanalysis.agent.planner.AgentPlan.Status;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService.ActiveSkill;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Phase-one Agent dispatcher. It deliberately performs no direct SmartBI/RAG
 * calls: all business execution remains inside the selected skill.
 */
@Service
public class AgentOrchestrator {

    private final SupervisorPlanner supervisorPlanner;
    private final AgentPlanExecutor planExecutor;
    private final ChatConversationMemoryService memoryService;

    public AgentOrchestrator(
            SupervisorPlanner supervisorPlanner,
            AgentPlanExecutor planExecutor,
            ChatConversationMemoryService memoryService) {
        this.supervisorPlanner = supervisorPlanner;
        this.planExecutor = planExecutor;
        this.memoryService = memoryService;
    }

    public AgentResponse respond(AgentRequest request) {
        AgentContext context = new AgentContext(
                request.userId(), request.conversationId(), request.entryMode(), request.model(), request.confirmed(),
                request.queryContext(), request.attributionTemplate(), request.attributionExecutionOptions(), request.action());
        Optional<ActiveSkill> activeSkill = memoryService.activeSkill(request.userId(), request.conversationId());
        if (activeSkill.isPresent() && cancellation(request.message())) {
            ActiveSkill cancelled = activeSkill.get();
            memoryService.cancelActiveSkill(
                    request.userId(), request.conversationId(), cancelled, request.message());
            AgentPlan plan = activeSkillPlan(request.message(), cancelled);
            return new AgentResponse(
                    "cancelled", cancelled.name(), "已取消当前" + cancelled.displayName() + "。",
                    request.conversationId(),
                    new AgentViewModel("flow-cancelled", Map.of("skill", cancelled.name())),
                    cancelled.name().toLowerCase(), List.of(), plan);
        }
        AgentPlan plan = activeSkill
                .map(skill -> activeSkillPlan(request.message(), skill))
                .orElseGet(() -> supervisorPlanner.plan(request, context));
        return planExecutor.execute(plan, request, context);
    }

    private AgentPlan activeSkillPlan(String goal, ActiveSkill activeSkill) {
        String skillId = activeSkill.name().toLowerCase();
        PlanStep step = new PlanStep(
                "step-1", CapabilityType.SKILL, skillId, List.of(), Map.of(), List.of());
        return new AgentPlan(
                "plan_" + UUID.randomUUID().toString().replace("-", ""), 1,
                "active-skill-owner", goal == null ? "" : goal.trim(), Status.READY,
                "当前多轮任务继续由 " + activeSkill.name() + " Skill 接管", List.of(step), Instant.now());
    }

    private boolean cancellation(String message) {
        if (message == null) return false;
        String normalized = message.replaceAll("[，。！？!?,\\s]", "").trim();
        return normalized.equals("取消") || normalized.equals("算了") || normalized.equals("不查了")
                || normalized.equals("停止") || normalized.equals("退出")
                || normalized.contains("取消当前查询") || normalized.contains("取消查询")
                || normalized.contains("停止查询") || normalized.contains("退出查询")
                || normalized.contains("取消归因") || normalized.contains("停止归因")
                || normalized.contains("退出归因");
    }
}
