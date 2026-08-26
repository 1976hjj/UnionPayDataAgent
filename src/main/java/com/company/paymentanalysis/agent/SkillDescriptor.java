package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.util.Set;

/** Stable capability contract consumed by the future registry and planner. */
public record SkillDescriptor(
        String skillId,
        String displayName,
        String description,
        AgentEntryMode entryMode,
        Set<AgentAction> supportedActions,
        Set<ArtifactType> acceptedArtifactTypes,
        Set<ArtifactType> producedArtifactTypes,
        boolean confirmationRequired) {

    public SkillDescriptor {
        if (skillId == null || !skillId.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Skill ID 无效");
        }
        if (entryMode == null) {
            throw new IllegalArgumentException("Skill 入口不能为空");
        }
        supportedActions = supportedActions == null ? Set.of() : Set.copyOf(supportedActions);
        acceptedArtifactTypes = acceptedArtifactTypes == null ? Set.of() : Set.copyOf(acceptedArtifactTypes);
        producedArtifactTypes = producedArtifactTypes == null ? Set.of() : Set.copyOf(producedArtifactTypes);
    }
}
