package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.agent.planner.AgentPlan;
import com.company.paymentanalysis.agent.planner.AgentPlanExecutor;
import com.company.paymentanalysis.agent.planner.SupervisorPlanner;
import org.springframework.stereotype.Service;

/**
 * Phase-one Agent dispatcher. It deliberately performs no direct SmartBI/RAG
 * calls: all business execution remains inside the selected skill.
 */
@Service
public class AgentOrchestrator {

    private final SupervisorPlanner supervisorPlanner;
    private final AgentPlanExecutor planExecutor;

    public AgentOrchestrator(
            SupervisorPlanner supervisorPlanner,
            AgentPlanExecutor planExecutor) {
        this.supervisorPlanner = supervisorPlanner;
        this.planExecutor = planExecutor;
    }

    public AgentResponse respond(AgentRequest request) {
        AgentContext context = new AgentContext(
                request.userId(), request.conversationId(), request.entryMode(), request.model(), request.confirmed(),
                request.queryContext(), request.attributionTemplate(), request.attributionExecutionOptions(), request.action());
        AgentPlan plan = supervisorPlanner.plan(request, context);
        return planExecutor.execute(plan, request, context);
    }
}
