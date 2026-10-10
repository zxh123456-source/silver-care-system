USE db_beadhouse;
CREATE TABLE IF NOT EXISTS ai_care_alert (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_key VARCHAR(160) NOT NULL,
    elder_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    kind VARCHAR(40) NOT NULL,
    detail TEXT NOT NULL,
    evidence_key CHAR(64) NOT NULL,
    task_key VARCHAR(160) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    reviewer_id BIGINT NULL,
    revision INT NOT NULL DEFAULT 0,
    resolution_note VARCHAR(500) NULL,
    resolved_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_care_alert_key(alert_key),
    KEY idx_care_alert_scope(elder_id,state,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康变化与用药待核对告警';
