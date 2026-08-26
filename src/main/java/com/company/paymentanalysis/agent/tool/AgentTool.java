package com.company.paymentanalysis.agent.tool;

/** A bounded deterministic operation callable by a planner or skill. */
public interface AgentTool {

    ToolDescriptor descriptor();

    ToolExecutionResult execute(ToolExecutionRequest request);
}
