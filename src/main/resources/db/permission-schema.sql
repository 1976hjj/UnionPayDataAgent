CREATE TABLE IF NOT EXISTS permission_dimension (
    dimension_id VARCHAR(64) PRIMARY KEY,
    required INTEGER NOT NULL DEFAULT 1,
    enabled INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_data_scope (
    login_username VARCHAR(120) NOT NULL,
    dimension_id VARCHAR(64) NOT NULL,
    dimension_value VARCHAR(200) NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (login_username, dimension_id, dimension_value),
    FOREIGN KEY (dimension_id) REFERENCES permission_dimension(dimension_id)
);

CREATE INDEX IF NOT EXISTS idx_user_data_scope_user
    ON user_data_scope(login_username, enabled);
