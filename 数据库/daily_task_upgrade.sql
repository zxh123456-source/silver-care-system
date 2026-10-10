USE db_beadhouse;
CREATE TABLE IF NOT EXISTS ai_daily_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_key VARCHAR(160) NOT NULL,
    task_date DATE NOT NULL,
    elder_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    category VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    detail TEXT,
    target_path VARCHAR(100) NOT NULL,
    due_at DATETIME NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    owner_id BIGINT NULL,
    revision INT NOT NULL DEFAULT 0,
    completion_note VARCHAR(500) NULL,
    completed_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_daily_task_key(task_key),
    KEY idx_daily_task_elder_state(elder_id,state,due_at),
    KEY idx_daily_task_owner(owner_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日协作任务；不代理原业务执行';
