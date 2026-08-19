package com.company.paymentanalysis.agent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Phase-one Agent dispatcher. It deliberately performs no direct SmartBI/RAG
 * calls: all business execution remains inside the selected skill.
 */
@Service
public class AgentOrchestrator {

    private final Map<AgentEntryMode, AgentSkill> skills;

    public AgentOrchestrator(List<AgentSkill> skills) {
        Map<AgentEntryMode, AgentSkill> indexed = new EnumMap<>(AgentEntryMode.class);
        for (AgentSkill skill : skills) {
            AgentSkill previous = indexed.putIfAbsent(skill.entryMode(), skill);
            if (previous != null) {
                throw new IllegalStateException("重复注册 Agent Skill：" + skill.entryMode());
            }
        }
        this.skills = Map.copyOf(indexed);
    }

    public AgentResponse respond(AgentRequest request) {
        AgentContext context = new AgentContext(
                request.userId(), request.conversationId(), request.entryMode(), request.model(), request.confirmed(),
                request.queryContext(), request.attributionTemplate(), request.attributionExecutionOptions(), request.action());
        AgentSkill skill = skills.get(context.entryMode());
        if (skill == null) {
            throw new IllegalArgumentException("当前入口暂不支持：" + context.entryMode());
        }
        return skill.execute(request, context);
    }
}
