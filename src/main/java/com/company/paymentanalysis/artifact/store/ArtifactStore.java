package com.company.paymentanalysis.artifact.store;

import com.company.paymentanalysis.artifact.model.Artifact;
import java.util.List;
import java.util.Optional;

public interface ArtifactStore {

    Artifact save(Artifact artifact);

    Optional<Artifact> findById(String artifactId);

    List<Artifact> findByConversation(String ownerUserId, String conversationId);

    void addLineage(String parentArtifactId, String childArtifactId, String relationType, String stepId);
}
