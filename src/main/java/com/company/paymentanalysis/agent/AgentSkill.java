package com.company.paymentanalysis.agent;

/** A bounded business capability selected by the Agent orchestrator. */
public interface AgentSkill {

    AgentEntryMode entryMode();

    AgentResponse execute(AgentRequest request, AgentContext context);
}
