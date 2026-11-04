USE db_beadhouse;

-- Additive and repeatable. Existing evidence starts at revision 1; no historical reads are invented.
SET @has_notice_revision=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_care_alert' AND column_name='notice_revision');
SET @notice_sql=IF(@has_notice_revision=0,'ALTER TABLE ai_care_alert ADD COLUMN notice_revision BIGINT NOT NULL DEFAULT 1','SELECT 1');
PREPARE notice_stmt FROM @notice_sql;
EXECUTE notice_stmt;
DEALLOCATE PREPARE notice_stmt;

CREATE TABLE IF NOT EXISTS ai_alert_read_receipt (
    staff_id BIGINT NOT NULL,
    alert_id BIGINT NOT NULL,
    seen_notice_revision BIGINT NOT NULL DEFAULT 0,
    read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(staff_id,alert_id),
    CONSTRAINT chk_alert_seen_revision CHECK(seen_notice_revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工独立告警已读版本；不代表业务确认或解除';

CREATE TABLE IF NOT EXISTS ai_alert_scan_checkpoint (
    id BIGINT NOT NULL PRIMARY KEY,
    scan_date DATE NOT NULL,
    plan_cursor BIGINT NOT NULL DEFAULT 0,
    target_date DATE NOT NULL,
    last_completed_date DATE NULL,
    last_success_at DATETIME(3) NULL,
    last_error VARCHAR(160) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(3) NULL,
    next_scan_at DATETIME(3) NULL,
    CONSTRAINT chk_alert_scan_singleton CHECK(id=1),
    CONSTRAINT chk_alert_scan_cursor CHECK(plan_cursor>=0 AND lease_token>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统后台历史用药核对游标与租约；无人工操作者';
