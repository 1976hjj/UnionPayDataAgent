package com.company.paymentanalysis.agent.planner;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Immutable, UI-neutral execution plan produced by the supervisor. */
public record AgentPlan(
        String planId,
        int schemaVersion,
        String plannerId,
        String goal,
        Status status,
        String message,
        List<PlanStep> steps,
        Instant createdAt) implements Serializable {

    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (planId == null || planId.isBlank() || plannerId == null || plannerId.isBlank()) {
            throw new IllegalArgumentException("计划标识和 Planner 标识不能为空");
        }
        if (status == null || createdAt == null) {
            throw new IllegalArgumentException("计划状态和创建时间不能为空");
        }
        if (status == Status.READY && steps.isEmpty()) {
            throw new IllegalArgumentException("可执行计划必须包含步骤");
        }
        Set<String> stepIds = new HashSet<>();
        for (PlanStep step : steps) {
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException("计划步骤标识重复：" + step.stepId());
            }
        }
        for (PlanStep step : steps) {
            if (!stepIds.containsAll(step.dependsOn()) || step.dependsOn().contains(step.stepId())) {
                throw new IllegalArgumentException("计划步骤依赖无效：" + step.stepId());
            }
        }
    }

    public enum Status { READY, NEEDS_INPUT }

    public record PlanStep(
            String stepId,
            CapabilityType capabilityType,
            String capabilityId,
            List<String> inputArtifactIds,
            Map<String, Object> arguments,
            List<String> dependsOn) implements Serializable {

        public PlanStep {
            if (stepId == null || stepId.isBlank() || capabilityType == null
                    || capabilityId == null || capabilityId.isBlank()) {
                throw new IllegalArgumentException("计划步骤定义不完整");
            }
            inputArtifactIds = inputArtifactIds == null ? List.of() : List.copyOf(inputArtifactIds);
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    public enum CapabilityType { SKILL, TOOL }
}
