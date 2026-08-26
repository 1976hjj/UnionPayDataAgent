package com.company.paymentanalysis.artifact.service;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.store.ArtifactStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/** Owner-scoped read boundary used by controllers, planners and downstream skills. */
@Service
public class ArtifactAccessService {

    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public ArtifactAccessService(ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    public ArtifactView get(String ownerUserId, String artifactId) {
        Artifact artifact = requireOwned(ownerUserId, artifactId);
        return view(artifact, true);
    }

    /** Internal boundary for services that need storage metadata after ownership validation. */
    public Artifact requireOwnedArtifact(String ownerUserId, String artifactId) {
        return requireOwned(ownerUserId, artifactId);
    }

    public List<ArtifactView> findByConversation(String ownerUserId, String conversationId) {
        String owner = requiredIdentifier(ownerUserId, "Artifact 所属用户无效");
        String conversation = requiredIdentifier(conversationId, "Artifact 所属会话无效");
        return artifactStore.findByConversation(owner, conversation).stream()
                .filter(artifact -> !expired(artifact))
                .map(artifact -> view(artifact, false))
                .toList();
    }

    public <T> T readPayload(
            String ownerUserId, String artifactId, ArtifactType expectedType, Class<T> payloadType) {
        Artifact artifact = requireOwned(ownerUserId, artifactId);
        if (artifact.artifactType() != expectedType) {
            throw new ArtifactTypeMismatchException(
                    "Artifact 类型不匹配：期望 " + expectedType + "，实际 " + artifact.artifactType());
        }
        verifyIntegrity(artifact);
        try {
            return objectMapper.readValue(artifact.payloadJson(), payloadType);
        } catch (JsonProcessingException exception) {
            throw new ArtifactIntegrityException("Artifact Payload 无法解析", exception);
        }
    }

    private Artifact requireOwned(String ownerUserId, String artifactId) {
        String owner = requiredIdentifier(ownerUserId, "Artifact 所属用户无效");
        String id = requiredIdentifier(artifactId, "Artifact 标识无效");
        Artifact artifact = artifactStore.findById(id)
                .orElseThrow(() -> new ArtifactNotFoundException("Artifact 不存在"));
        // Deliberately return the same result as a missing artifact to avoid leaking another user's data.
        if (!owner.equals(artifact.ownerUserId()) || expired(artifact)) {
            throw new ArtifactNotFoundException("Artifact 不存在");
        }
        return artifact;
    }

    private ArtifactView view(Artifact artifact, boolean includePayload) {
        JsonNode payload = null;
        if (includePayload && artifact.payloadJson() != null) {
            verifyIntegrity(artifact);
            try {
                payload = objectMapper.readTree(artifact.payloadJson());
            } catch (JsonProcessingException exception) {
                throw new ArtifactIntegrityException("Artifact Payload 无法解析", exception);
            }
        }
        return new ArtifactView(
                artifact.artifactId(), artifact.artifactType(), artifact.schemaVersion(), artifact.status(),
                artifact.title(), artifact.ownerUserId(), artifact.conversationId(), artifact.sourceMessageId(),
                artifact.createdBy(), artifact.storageType(), artifact.rowCount(), artifact.createdAt(),
                artifact.expiresAt(), payload);
    }

    private void verifyIntegrity(Artifact artifact) {
        if (artifact.payloadJson() == null || artifact.payloadChecksum() == null) {
            throw new ArtifactIntegrityException("Artifact Payload 或校验值缺失");
        }
        String actual = sha256(artifact.payloadJson());
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                artifact.payloadChecksum().getBytes(StandardCharsets.US_ASCII))) {
            throw new ArtifactIntegrityException("Artifact Payload 完整性校验失败");
        }
    }

    private boolean expired(Artifact artifact) {
        return artifact.expiresAt() != null && !artifact.expiresAt().isAfter(Instant.now());
    }

    private String requiredIdentifier(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 120
                || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    public record ArtifactView(
            String artifactId,
            ArtifactType artifactType,
            int schemaVersion,
            Artifact.Status status,
            String title,
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String createdBy,
            Artifact.StorageType storageType,
            Long rowCount,
            Instant createdAt,
            Instant expiresAt,
            JsonNode payload) {
    }

    public static class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException(String message) {
            super(message);
        }
    }

    public static class ArtifactTypeMismatchException extends RuntimeException {
        public ArtifactTypeMismatchException(String message) {
            super(message);
        }
    }

    public static class ArtifactIntegrityException extends RuntimeException {
        public ArtifactIntegrityException(String message) {
            super(message);
        }

        public ArtifactIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
