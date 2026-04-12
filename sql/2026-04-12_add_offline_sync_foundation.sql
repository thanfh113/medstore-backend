CREATE TABLE IF NOT EXISTS sync_changes (
    id VARCHAR(36) PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    operation VARCHAR(20) NOT NULL,
    payload_json LONGTEXT NULL,
    source_device_id VARCHAR(128) NULL,
    client_mutation_id VARCHAR(64) NULL,
    server_version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id VARCHAR(36) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sync_changes_shop_version (shop_id, server_version),
    UNIQUE KEY uk_sync_changes_shop_mutation (shop_id, client_mutation_id),
    CONSTRAINT fk_sync_changes_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
    CONSTRAINT fk_sync_changes_user FOREIGN KEY (created_by_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS sync_checkpoints (
    id VARCHAR(36) PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    last_pulled_version BIGINT NOT NULL DEFAULT 0,
    last_pushed_version BIGINT NOT NULL DEFAULT 0,
    last_sync_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sync_checkpoints_shop_device (shop_id, device_id),
    CONSTRAINT fk_sync_checkpoints_shop FOREIGN KEY (shop_id) REFERENCES shops(id)
);

CREATE TABLE IF NOT EXISTS sync_jobs (
    id VARCHAR(36) PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    device_id VARCHAR(128) NULL,
    job_type VARCHAR(30) NOT NULL DEFAULT 'INCREMENTAL',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    error_message LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sync_jobs_shop_status (shop_id, status),
    CONSTRAINT fk_sync_jobs_shop FOREIGN KEY (shop_id) REFERENCES shops(id)
);
