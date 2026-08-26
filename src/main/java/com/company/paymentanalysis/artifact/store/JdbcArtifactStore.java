package com.company.paymentanalysis.artifact.store;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.Artifact.Status;
import com.company.paymentanalysis.artifact.model.Artifact.StorageType;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcArtifactStore implements ArtifactStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcArtifactStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Artifact save(Artifact artifact) {
        jdbcTemplate.update("""
                INSERT INTO bi_agent_artifact (
                    artifact_id, artifact_type, schema_version, status, title,
                    owner_user_id, conversation_id, source_message_id, created_by,
                    storage_type, payload_json, payload_uri, payload_checksum, row_count,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                artifact.artifactId(), artifact.artifactType().name(), artifact.schemaVersion(),
                artifact.status().name(), artifact.title(), artifact.ownerUserId(),
                artifact.conversationId(), artifact.sourceMessageId(), artifact.createdBy(),
                artifact.storageType().name(), artifact.payloadJson(), artifact.payloadUri(),
                artifact.payloadChecksum(), artifact.rowCount(), timestamp(artifact.createdAt()),
                timestamp(artifact.expiresAt()));
        return artifact;
    }

    @Override
    public Optional<Artifact> findById(String artifactId) {
        return jdbcTemplate.query("""
                SELECT * FROM bi_agent_artifact WHERE artifact_id = ?
                """, this::map, artifactId).stream().findFirst();
    }

    @Override
    public List<Artifact> findByConversation(String ownerUserId, String conversationId) {
        return jdbcTemplate.query("""
                SELECT * FROM bi_agent_artifact
                WHERE owner_user_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, artifact_id DESC
                """, this::map, ownerUserId, conversationId);
    }

    @Override
    public void addLineage(
            String parentArtifactId, String childArtifactId, String relationType, String stepId) {
        jdbcTemplate.update("""
                INSERT INTO bi_agent_artifact_lineage (
                    child_artifact_id, parent_artifact_id, relation_type, step_id
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(child_artifact_id, parent_artifact_id, relation_type)
                DO UPDATE SET step_id = excluded.step_id
                """, childArtifactId, parentArtifactId, relationType, stepId);
    }

    private Artifact map(ResultSet resultSet, int rowNumber) throws SQLException {
        long rowCount = resultSet.getLong("row_count");
        return new Artifact(
                resultSet.getString("artifact_id"),
                ArtifactType.valueOf(resultSet.getString("artifact_type")),
                resultSet.getInt("schema_version"),
                Status.valueOf(resultSet.getString("status")),
                resultSet.getString("title"),
                resultSet.getString("owner_user_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("source_message_id"),
                resultSet.getString("created_by"),
                StorageType.valueOf(resultSet.getString("storage_type")),
                resultSet.getString("payload_json"),
                resultSet.getString("payload_uri"),
                resultSet.getString("payload_checksum"),
                resultSet.wasNull() ? null : rowCount,
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet.getTimestamp("expires_at")));
    }

    private Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
