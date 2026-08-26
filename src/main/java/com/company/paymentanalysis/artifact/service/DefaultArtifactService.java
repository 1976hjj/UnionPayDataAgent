package com.company.paymentanalysis.artifact.service;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.Artifact.Status;
import com.company.paymentanalysis.artifact.model.Artifact.StorageType;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.store.ArtifactStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultArtifactService implements ArtifactService {

    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public DefaultArtifactService(ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public Artifact createQueryResult(CreateQueryResult command) {
        if (command == null || command.payload() == null) {
            throw new IllegalArgumentException("查询 Artifact 内容不能为空");
        }
        String payloadJson = json(command.payload());
        return saveInline(
                ArtifactType.QUERY_RESULT, command.title(), command.ownerUserId(),
                command.conversationId(), command.sourceMessageId(), "query-skill",
                payloadJson, command.payload().totalRows());
    }

    @Override
    public Artifact createAttributionResult(CreateAttributionResult command) {
        if (command == null || command.payload() == null) {
            throw new IllegalArgumentException("归因 Artifact 内容不能为空");
        }
        return saveInline(
                ArtifactType.ATTRIBUTION_RESULT, command.title(), command.ownerUserId(),
                command.conversationId(), command.sourceMessageId(), "attribution-skill",
                json(command.payload()), null);
    }

    @Override
    @Transactional
    public Artifact createChart(CreateChart command) {
        if (command == null || command.payload() == null) {
            throw new IllegalArgumentException("图表 Artifact 内容不能为空");
        }
        String sourceArtifactId = required(command.sourceArtifactId(), "图表来源 Artifact 不能为空");
        Artifact chart = saveInline(
                ArtifactType.CHART, command.title(), command.ownerUserId(),
                command.conversationId(), command.sourceMessageId(), "visualization-skill",
                json(command.payload()), (long) command.payload().categories().size());
        artifactStore.addLineage(
                sourceArtifactId,
                chart.artifactId(), "VISUALIZED_FROM", null);
        return chart;
    }

    @Override
    @Transactional
    public Artifact createFile(CreateFile command) {
        if (command == null || command.payload() == null) {
            throw new IllegalArgumentException("文件 Artifact 内容不能为空");
        }
        String sourceArtifactId = required(command.sourceArtifactId(), "文件来源 Artifact 不能为空");
        String storageKey = required(command.storageKey(), "文件存储标识不能为空");
        String payloadJson = json(command.payload());
        Artifact file = new Artifact(
                "art_" + UUID.randomUUID().toString().replace("-", ""), ArtifactType.FILE, 1,
                Status.COMPLETED, command.title(),
                required(command.ownerUserId(), "Artifact 所属用户不能为空"),
                required(command.conversationId(), "Artifact 所属会话不能为空"),
                command.sourceMessageId(), "export-data-tool", StorageType.FILE,
                payloadJson, storageKey, sha256(payloadJson), command.rowCount(), Instant.now(), null);
        Artifact saved = artifactStore.save(file);
        artifactStore.addLineage(sourceArtifactId, saved.artifactId(), "EXPORTED_FROM", null);
        return saved;
    }

    private Artifact saveInline(
            ArtifactType type, String title, String ownerUserId, String conversationId,
            String sourceMessageId, String createdBy, String payloadJson, Long rowCount) {
        Artifact artifact = new Artifact(
                "art_" + UUID.randomUUID().toString().replace("-", ""), type, 1,
                Status.COMPLETED, title,
                required(ownerUserId, "Artifact 所属用户不能为空"),
                required(conversationId, "Artifact 所属会话不能为空"),
                sourceMessageId, createdBy, StorageType.INLINE, payloadJson, null,
                sha256(payloadJson), rowCount, Instant.now(), null);
        return artifactStore.save(artifact);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("查询 Artifact 序列化失败", exception);
        }
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

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
