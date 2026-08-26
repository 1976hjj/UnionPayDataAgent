CREATE TABLE IF NOT EXISTS bi_agent_artifact (
    artifact_id VARCHAR(64) PRIMARY KEY,
    artifact_type VARCHAR(40) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL,
    title VARCHAR(300),
    owner_user_id VARCHAR(120) NOT NULL,
    conversation_id VARCHAR(120),
    source_message_id VARCHAR(120),
    created_by VARCHAR(100) NOT NULL,
    storage_type VARCHAR(20) NOT NULL,
    payload_json TEXT,
    payload_uri TEXT,
    payload_checksum VARCHAR(128),
    row_count INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bi_agent_artifact_conversation
    ON bi_agent_artifact(owner_user_id, conversation_id, created_at);

CREATE INDEX IF NOT EXISTS idx_bi_agent_artifact_type
    ON bi_agent_artifact(artifact_type, created_at);

CREATE TABLE IF NOT EXISTS bi_agent_artifact_lineage (
    child_artifact_id VARCHAR(64) NOT NULL,
    parent_artifact_id VARCHAR(64) NOT NULL,
    relation_type VARCHAR(40) NOT NULL,
    step_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (child_artifact_id, parent_artifact_id, relation_type),
    FOREIGN KEY (child_artifact_id) REFERENCES bi_agent_artifact(artifact_id),
    FOREIGN KEY (parent_artifact_id) REFERENCES bi_agent_artifact(artifact_id)
);

CREATE INDEX IF NOT EXISTS idx_bi_agent_artifact_lineage_parent
    ON bi_agent_artifact_lineage(parent_artifact_id, created_at);
