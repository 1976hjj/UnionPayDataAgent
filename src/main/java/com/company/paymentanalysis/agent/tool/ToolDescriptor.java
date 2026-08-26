package com.company.paymentanalysis.agent.tool;

import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.util.Set;

public record ToolDescriptor(
        String toolId,
        String displayName,
        String description,
        Set<ArtifactType> acceptedArtifactTypes,
        Set<ArtifactType> producedArtifactTypes,
        SideEffect sideEffect) {

    public ToolDescriptor {
        if (toolId == null || !toolId.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Tool ID 无效");
        }
        acceptedArtifactTypes = acceptedArtifactTypes == null ? Set.of() : Set.copyOf(acceptedArtifactTypes);
        producedArtifactTypes = producedArtifactTypes == null ? Set.of() : Set.copyOf(producedArtifactTypes);
        sideEffect = sideEffect == null ? SideEffect.READ_ONLY : sideEffect;
    }

    public enum SideEffect {
        READ_ONLY,
        CREATES_ARTIFACT,
        EXTERNAL_WRITE
    }
}
