USE db_beadhouse;

-- Existing hashes remain historical until a real source snapshot is queued as version 1.
SET @rag_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='policy_rag_sync' AND column_name='desired_version');
SET @rag_sql=IF(@rag_exists=0,'ALTER TABLE policy_rag_sync ADD COLUMN desired_version BIGINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE rag_stmt FROM @rag_sql;
EXECUTE rag_stmt;
DEALLOCATE PREPARE rag_stmt;

SET @rag_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='policy_rag_sync' AND column_name='payload_json');
SET @rag_sql=IF(@rag_exists=0,'ALTER TABLE policy_rag_sync ADD COLUMN payload_json LONGTEXT NULL','SELECT 1');
PREPARE rag_stmt FROM @rag_sql;
EXECUTE rag_stmt;
DEALLOCATE PREPARE rag_stmt;

SET @rag_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='policy_rag_sync' AND column_name='lease_owner');
SET @rag_sql=IF(@rag_exists=0,'ALTER TABLE policy_rag_sync ADD COLUMN lease_owner VARCHAR(64) NULL','SELECT 1');
PREPARE rag_stmt FROM @rag_sql;
EXECUTE rag_stmt;
DEALLOCATE PREPARE rag_stmt;

SET @rag_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='policy_rag_sync' AND column_name='lease_token');
SET @rag_sql=IF(@rag_exists=0,'ALTER TABLE policy_rag_sync ADD COLUMN lease_token BIGINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE rag_stmt FROM @rag_sql;
EXECUTE rag_stmt;
DEALLOCATE PREPARE rag_stmt;

SET @rag_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='policy_rag_sync' AND column_name='lease_until');
SET @rag_sql=IF(@rag_exists=0,'ALTER TABLE policy_rag_sync ADD COLUMN lease_until DATETIME(3) NULL','SELECT 1');
PREPARE rag_stmt FROM @rag_sql;
EXECUTE rag_stmt;
DEALLOCATE PREPARE rag_stmt;

SET @rag_exists=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='policy_rag_sync' AND index_name='idx_policy_rag_lease');
SET @rag_sql=IF(@rag_exists=0,'ALTER TABLE policy_rag_sync ADD INDEX idx_policy_rag_lease(status,lease_until,id)','SELECT 1');
PREPARE rag_stmt FROM @rag_sql;
EXECUTE rag_stmt;
DEALLOCATE PREPARE rag_stmt;

-- No unversioned SYNCED row is treated as a successful v2 publication.
UPDATE policy_rag_sync SET status='PENDING',next_retry_time=NOW(),last_error_code=NULL
WHERE desired_version=0 AND payload_json IS NULL;
