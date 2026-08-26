package com.company.paymentanalysis.artifact.model;

import java.time.Instant;

/** Immutable metadata and storage envelope for every agent-produced artifact. */
public record Artifact(
        String artifactId,
        ArtifactType artifactType,
        int schemaVersion,
        Status status,
        String title,
        String ownerUserId,
        String conversationId,
        String sourceMessageId,
        String createdBy,
        StorageType storageType,
        String payloadJson,
        String payloadUri,
        String payloadChecksum,
        Long rowCount,
        Instant createdAt,
        Instant expiresAt) {

    public enum Status { PENDING, COMPLETED, FAILED }

    public enum StorageType { INLINE, FILE, OBJECT_STORAGE }
}
