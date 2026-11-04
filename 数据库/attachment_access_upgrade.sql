USE db_beadhouse;

-- Existing uploads are not drafts. Preserve business URLs, bytes and provenance.
SET @attachment_state_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='base_attachment' AND column_name='binding_state')=0,
  'ALTER TABLE base_attachment ADD COLUMN binding_state VARCHAR(16) NOT NULL DEFAULT ''LEGACY''',
  'SELECT 1');
PREPARE attachment_ddl FROM @attachment_state_ddl;
EXECUTE attachment_ddl;
DEALLOCATE PREPARE attachment_ddl;
SET @attachment_media_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
   AND table_name='base_attachment' AND column_name='media_type')=0,
  'ALTER TABLE base_attachment ADD COLUMN media_type VARCHAR(64) NULL DEFAULT NULL',
  'SELECT 1');
PREPARE attachment_ddl FROM @attachment_media_ddl;
EXECUTE attachment_ddl;
DEALLOCATE PREPARE attachment_ddl;

CREATE TABLE IF NOT EXISTS attachment_reference (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  attachment_id BIGINT NOT NULL,
  owner_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id BIGINT NOT NULL,
  active CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_id BIGINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_id BIGINT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_attachment_owner (attachment_id, owner_type, owner_id),
  KEY idx_reference_owner (owner_type, owner_id, active, attachment_id),
  CONSTRAINT chk_attachment_reference_active CHECK (active IN ('Y','N')),
  CONSTRAINT chk_attachment_reference_owner CHECK (owner_type IN ('STAFF','ACCIDENT','ACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Permanent attachment ownership; retired references remain';

-- Exact byte equality only: never compare a basename or rewrite the host.
-- Deleted business rows retain history and cannot make a file a draft again.
DROP TEMPORARY TABLE IF EXISTS attachment_migration_owners;
CREATE TEMPORARY TABLE attachment_migration_owners (
  owner_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id BIGINT NOT NULL,
  resource_url VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  active CHAR(1) NOT NULL,
  PRIMARY KEY (owner_type, owner_id)
);
INSERT INTO attachment_migration_owners
SELECT 'STAFF',id,avator,IF(leave_flag='N','Y','N') FROM staff WHERE avator IS NOT NULL AND avator<>''
UNION ALL
SELECT 'ACCIDENT',id,picture,IF(del_flag='N','Y','N') FROM accident WHERE picture IS NOT NULL AND picture<>''
UNION ALL
SELECT 'ACTIVE',id,active_picture,IF(del_flag='N','Y','N') FROM active WHERE active_picture IS NOT NULL AND active_picture<>'';

DROP TEMPORARY TABLE IF EXISTS attachment_migration_matches;
CREATE TEMPORARY TABLE attachment_migration_matches AS
SELECT a.id attachment_id,o.owner_type,o.owner_id,o.active
FROM base_attachment a JOIN attachment_migration_owners o
  ON CAST(a.url AS BINARY)=CAST(o.resource_url AS BINARY)
WHERE a.url<>'';

-- A URL identifying multiple metadata rows is ambiguous even with one owner.
DROP TEMPORARY TABLE IF EXISTS attachment_migration_ambiguous;
CREATE TEMPORARY TABLE attachment_migration_ambiguous AS
SELECT owner_type,owner_id,COUNT(*) metadata_count
FROM attachment_migration_matches GROUP BY owner_type,owner_id HAVING COUNT(*)>1;

START TRANSACTION;
INSERT INTO attachment_reference (attachment_id,owner_type,owner_id,active)
SELECT m.attachment_id,m.owner_type,m.owner_id,m.active
FROM attachment_migration_matches m JOIN base_attachment a ON a.id=m.attachment_id
WHERE a.binding_state='LEGACY'
  AND NOT EXISTS (SELECT 1 FROM attachment_reference r WHERE r.attachment_id=m.attachment_id
    AND r.owner_type=m.owner_type AND r.owner_id=m.owner_id);

UPDATE base_attachment a JOIN (
  SELECT attachment_id,COUNT(*) owner_count FROM attachment_reference GROUP BY attachment_id
) r ON r.attachment_id=a.id
SET a.binding_state=IF(r.owner_count=1,'BOUND','CONFLICT')
WHERE a.binding_state='LEGACY';

UPDATE base_attachment a JOIN attachment_migration_matches m ON m.attachment_id=a.id
JOIN attachment_migration_ambiguous ambiguous
  ON ambiguous.owner_type=m.owner_type AND ambiguous.owner_id=m.owner_id
SET a.binding_state='CONFLICT'
WHERE a.binding_state IN ('LEGACY','BOUND');
COMMIT;

-- Reports deliberately omit URLs and paths. GET /file/diagnostics reports disk
-- availability separately, with current administrator authentication.
SELECT binding_state,COUNT(*) attachment_count FROM base_attachment GROUP BY binding_state;
SELECT owner_type,COUNT(*) unmatched_reference_count FROM attachment_migration_owners o
WHERE NOT EXISTS (SELECT 1 FROM attachment_migration_matches m
  WHERE m.owner_type=o.owner_type AND m.owner_id=o.owner_id) GROUP BY owner_type;
SELECT a.id attachment_id,r.owner_type,r.owner_id,r.active
FROM base_attachment a JOIN attachment_reference r ON r.attachment_id=a.id
WHERE a.binding_state='CONFLICT' ORDER BY a.id,r.id LIMIT 100;
SELECT o.owner_type,o.owner_id FROM attachment_migration_owners o
WHERE NOT EXISTS (SELECT 1 FROM attachment_migration_matches m
  WHERE m.owner_type=o.owner_type AND m.owner_id=o.owner_id)
ORDER BY o.owner_type,o.owner_id LIMIT 100;
DROP TEMPORARY TABLE attachment_migration_ambiguous;
DROP TEMPORARY TABLE attachment_migration_matches;
DROP TEMPORARY TABLE attachment_migration_owners;
