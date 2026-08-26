package com.company.paymentanalysis.agent;

/** A bounded business capability selected by the Agent orchestrator. */
public interface AgentSkill {

    SkillDescriptor descriptor();

    default AgentEntryMode entryMode() {
        return descriptor().entryMode();
    }

    AgentResponse execute(AgentRequest request, AgentContext context);
}
