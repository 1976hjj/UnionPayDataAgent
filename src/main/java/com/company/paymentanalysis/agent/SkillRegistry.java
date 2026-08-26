package com.company.paymentanalysis.agent;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Immutable startup-time registry of business skills. */
@Service
public class SkillRegistry {

    private final Map<String, AgentSkill> byId;
    private final Map<AgentEntryMode, AgentSkill> byEntryMode;
    private final List<SkillDescriptor> descriptors;

    public SkillRegistry(List<AgentSkill> skills) {
        Map<String, AgentSkill> idIndex = new LinkedHashMap<>();
        Map<AgentEntryMode, AgentSkill> entryIndex = new EnumMap<>(AgentEntryMode.class);
        for (AgentSkill skill : skills) {
            if (skill == null || skill.descriptor() == null) {
                throw new IllegalStateException("Agent Skill 及其描述不能为空");
            }
            SkillDescriptor descriptor = skill.descriptor();
            AgentSkill duplicateId = idIndex.putIfAbsent(descriptor.skillId(), skill);
            if (duplicateId != null) {
                throw new IllegalStateException("重复注册 Agent Skill ID：" + descriptor.skillId());
            }
            AgentSkill duplicateEntry = entryIndex.putIfAbsent(descriptor.entryMode(), skill);
            if (duplicateEntry != null) {
                throw new IllegalStateException("重复注册 Agent Skill 入口：" + descriptor.entryMode());
            }
        }
        this.byId = Map.copyOf(idIndex);
        this.byEntryMode = Map.copyOf(entryIndex);
        this.descriptors = idIndex.values().stream()
                .map(AgentSkill::descriptor)
                .sorted(Comparator.comparing(SkillDescriptor::skillId))
                .toList();
    }

    public AgentSkill require(String skillId) {
        return find(skillId).orElseThrow(() ->
                new IllegalArgumentException("当前不支持 Skill：" + skillId));
    }

    public AgentSkill require(AgentEntryMode entryMode) {
        if (entryMode == null) {
            throw new IllegalArgumentException("Skill 入口不能为空");
        }
        AgentSkill skill = byEntryMode.get(entryMode);
        if (skill == null) {
            throw new IllegalArgumentException("当前入口暂不支持：" + entryMode);
        }
        return skill;
    }

    public Optional<AgentSkill> find(String skillId) {
        String normalized = skillId == null ? "" : skillId.trim().toLowerCase();
        return Optional.ofNullable(byId.get(normalized));
    }

    public List<SkillDescriptor> descriptors() {
        return descriptors;
    }
}
