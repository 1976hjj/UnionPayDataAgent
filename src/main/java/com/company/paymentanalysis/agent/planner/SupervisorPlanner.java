package com.company.paymentanalysis.agent.planner;

import com.company.paymentanalysis.agent.AgentContext;
import com.company.paymentanalysis.agent.AgentRequest;

/** Replaceable brain boundary. It plans capabilities but never executes business operations. */
public interface SupervisorPlanner {

    AgentPlan plan(AgentRequest request, AgentContext context);
}
