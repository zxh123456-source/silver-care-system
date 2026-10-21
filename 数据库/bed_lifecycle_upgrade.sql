USE db_beadhouse;

-- Stop application writers while upgrading. Additive DDL cannot be rolled back by MySQL.
-- Report inconsistent IDs and abort; never choose a winner or invent a historical bed.
DROP PROCEDURE IF EXISTS upgrade_bed_lifecycle;
DELIMITER $$
CREATE PROCEDURE upgrade_bed_lifecycle()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='reserve' AND column_name='bed_id') THEN
    ALTER TABLE reserve ADD COLUMN bed_id BIGINT NULL COMMENT 'Immutable reservation bed; unknown legacy refund remains NULL';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='reserve' AND column_name='state') THEN
    ALTER TABLE reserve ADD COLUMN state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL;
  END IF;

  IF EXISTS (SELECT 1 FROM elder WHERE check_flag<>'已失效' GROUP BY id_num HAVING COUNT(*)>1) THEN
    SELECT GROUP_CONCAT(id ORDER BY id) AS conflicting_elder_ids FROM elder WHERE check_flag<>'已失效' GROUP BY id_num HAVING COUNT(*)>1;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: duplicate active identity; inspect elder IDs';
  END IF;
  IF EXISTS (SELECT 1 FROM elder WHERE check_flag IN ('预定','入住','退住审核') AND bed_id IS NOT NULL GROUP BY bed_id HAVING COUNT(*)>1) THEN
    SELECT bed_id,GROUP_CONCAT(id ORDER BY id) AS conflicting_elder_ids FROM elder WHERE check_flag IN ('预定','入住','退住审核') AND bed_id IS NOT NULL GROUP BY bed_id HAVING COUNT(*)>1;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: multiple occupants; inspect elder and bed IDs';
  END IF;
  IF EXISTS (SELECT 1 FROM elder e LEFT JOIN bed b ON b.id=e.bed_id WHERE
      (e.check_flag IN ('预定','入住','退住审核') AND (b.id IS NULL OR b.del_flag<>'N' OR b.bed_flag<>e.check_flag))
      OR (e.check_flag NOT IN ('预定','入住','退住审核') AND e.bed_id IS NOT NULL)) THEN
    SELECT e.id AS elder_id,e.bed_id,e.check_flag,b.bed_flag,b.del_flag FROM elder e LEFT JOIN bed b ON b.id=e.bed_id WHERE
      (e.check_flag IN ('预定','入住','退住审核') AND (b.id IS NULL OR b.del_flag<>'N' OR b.bed_flag<>e.check_flag))
      OR (e.check_flag NOT IN ('预定','入住','退住审核') AND e.bed_id IS NOT NULL);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: resident/bed mismatch; inspect IDs';
  END IF;
  IF EXISTS (SELECT 1 FROM bed b WHERE b.bed_flag IN ('预定','入住','退住审核') AND NOT EXISTS
      (SELECT 1 FROM elder e WHERE e.bed_id=b.id AND e.check_flag=b.bed_flag)) THEN
    SELECT b.id AS bed_id,b.bed_flag FROM bed b WHERE b.bed_flag IN ('预定','入住','退住审核') AND NOT EXISTS
      (SELECT 1 FROM elder e WHERE e.bed_id=b.id AND e.check_flag=b.bed_flag);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: occupied bed without matching resident';
  END IF;

  DROP TEMPORARY TABLE IF EXISTS bed_lifecycle_reservations;
  CREATE TEMPORARY TABLE bed_lifecycle_reservations AS
    SELECT r.id,r.elder_id,r.reserve_flag,
      COALESCE(r.state,IF(r.reserve_flag='Y','REFUNDED','ACTIVE')) AS state,
      IF(r.state IS NULL,IF(r.reserve_flag='N',e.bed_id,NULL),r.bed_id) AS bed_id
    FROM reserve r LEFT JOIN elder e ON e.id=r.elder_id;
  IF EXISTS (SELECT 1 FROM bed_lifecycle_reservations WHERE state NOT IN ('ACTIVE','REFUNDED','CONVERTED','EXPIRED')
      OR (state='REFUNDED' AND reserve_flag<>'Y') OR (state<>'REFUNDED' AND reserve_flag<>'N')
      OR (state<>'REFUNDED' AND bed_id IS NULL)) THEN
    SELECT id AS reserve_id,elder_id,bed_id,state,reserve_flag FROM bed_lifecycle_reservations WHERE state NOT IN ('ACTIVE','REFUNDED','CONVERTED','EXPIRED')
      OR (state='REFUNDED' AND reserve_flag<>'Y') OR (state<>'REFUNDED' AND reserve_flag<>'N')
      OR (state<>'REFUNDED' AND bed_id IS NULL);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: invalid reservation state or missing active bed';
  END IF;
  IF EXISTS (SELECT 1 FROM bed_lifecycle_reservations WHERE state='ACTIVE' GROUP BY elder_id HAVING COUNT(*)>1) THEN
    SELECT elder_id,GROUP_CONCAT(id ORDER BY id) AS reserve_ids FROM bed_lifecycle_reservations WHERE state='ACTIVE' GROUP BY elder_id HAVING COUNT(*)>1;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: multiple active reservations for one resident';
  END IF;
  IF EXISTS (SELECT 1 FROM bed_lifecycle_reservations WHERE state='ACTIVE' GROUP BY bed_id HAVING COUNT(*)>1) THEN
    SELECT bed_id,GROUP_CONCAT(id ORDER BY id) AS reserve_ids FROM bed_lifecycle_reservations WHERE state='ACTIVE' GROUP BY bed_id HAVING COUNT(*)>1;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: multiple active reservations for one bed';
  END IF;
  IF EXISTS (SELECT 1 FROM bed_lifecycle_reservations r LEFT JOIN elder e ON e.id=r.elder_id WHERE r.state='ACTIVE'
      AND (e.id IS NULL OR e.check_flag<>'预定' OR NOT (e.bed_id <=> r.bed_id))) THEN
    SELECT r.id AS reserve_id,r.elder_id,r.bed_id,e.bed_id AS current_bed_id,e.check_flag FROM bed_lifecycle_reservations r LEFT JOIN elder e ON e.id=r.elder_id
      WHERE r.state='ACTIVE' AND (e.id IS NULL OR e.check_flag<>'预定' OR NOT (e.bed_id <=> r.bed_id));
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: active reservation/current resident mismatch';
  END IF;
  IF EXISTS (SELECT 1 FROM elder e WHERE e.check_flag='预定' AND NOT EXISTS
      (SELECT 1 FROM bed_lifecycle_reservations r WHERE r.elder_id=e.id AND r.state='ACTIVE')) THEN
    SELECT e.id AS elder_id,e.bed_id FROM elder e WHERE e.check_flag='预定' AND NOT EXISTS
      (SELECT 1 FROM bed_lifecycle_reservations r WHERE r.elder_id=e.id AND r.state='ACTIVE');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: reserved resident without active reservation';
  END IF;
  IF EXISTS (SELECT 1 FROM retreat_apply WHERE apply_flag='待审核' GROUP BY elder_id HAVING COUNT(*)>1) THEN
    SELECT elder_id,GROUP_CONCAT(id ORDER BY id) AS apply_ids FROM retreat_apply WHERE apply_flag='待审核' GROUP BY elder_id HAVING COUNT(*)>1;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: multiple pending checkout applications';
  END IF;
  IF EXISTS (SELECT 1 FROM retreat_apply a LEFT JOIN elder e ON e.id=a.elder_id WHERE a.apply_flag='待审核' AND (e.id IS NULL OR e.check_flag<>'退住审核'))
      OR EXISTS (SELECT 1 FROM elder e WHERE e.check_flag='退住审核' AND NOT EXISTS (SELECT 1 FROM retreat_apply a WHERE a.elder_id=e.id AND a.apply_flag='待审核')) THEN
    SELECT a.id AS apply_id,a.elder_id,e.check_flag FROM retreat_apply a LEFT JOIN elder e ON e.id=a.elder_id WHERE a.apply_flag='待审核' AND (e.id IS NULL OR e.check_flag<>'退住审核');
    SELECT e.id AS elder_id FROM elder e WHERE e.check_flag='退住审核' AND NOT EXISTS (SELECT 1 FROM retreat_apply a WHERE a.elder_id=e.id AND a.apply_flag='待审核');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bed lifecycle preflight: pending checkout/resident mismatch';
  END IF;

  -- Only first-time legacy rows are backfilled. Refunded rows have no provable bed snapshot.
  UPDATE reserve r JOIN bed_lifecycle_reservations m ON m.id=r.id
    SET r.bed_id=m.bed_id,r.state=m.state WHERE r.state IS NULL;
  DROP TEMPORARY TABLE bed_lifecycle_reservations;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='reserve' AND column_name='state' AND is_nullable='YES') THEN
    ALTER TABLE reserve MODIFY COLUMN state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='elder' AND column_name='active_id_num') THEN
    ALTER TABLE elder ADD COLUMN active_id_num VARCHAR(18) GENERATED ALWAYS AS (CASE WHEN check_flag<>'已失效' THEN id_num ELSE NULL END) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='elder' AND index_name='uk_elder_active_identity') THEN
    ALTER TABLE elder ADD UNIQUE KEY uk_elder_active_identity(active_id_num);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='elder' AND column_name='active_bed_id') THEN
    ALTER TABLE elder ADD COLUMN active_bed_id BIGINT GENERATED ALWAYS AS (CASE WHEN check_flag IN ('预定','入住','退住审核') THEN bed_id ELSE NULL END) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='elder' AND index_name='uk_elder_active_bed') THEN
    ALTER TABLE elder ADD UNIQUE KEY uk_elder_active_bed(active_bed_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='reserve' AND column_name='active_elder_id') THEN
    ALTER TABLE reserve ADD COLUMN active_elder_id BIGINT GENERATED ALWAYS AS (CASE WHEN state='ACTIVE' THEN elder_id ELSE NULL END) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='reserve' AND column_name='active_bed_id') THEN
    ALTER TABLE reserve ADD COLUMN active_bed_id BIGINT GENERATED ALWAYS AS (CASE WHEN state='ACTIVE' THEN bed_id ELSE NULL END) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='reserve' AND index_name='uk_reserve_active_elder') THEN
    ALTER TABLE reserve ADD UNIQUE KEY uk_reserve_active_elder(active_elder_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='reserve' AND index_name='uk_reserve_active_bed') THEN
    ALTER TABLE reserve ADD UNIQUE KEY uk_reserve_active_bed(active_bed_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='reserve' AND index_name='idx_reserve_expiration') THEN
    ALTER TABLE reserve ADD KEY idx_reserve_expiration(state,due_date,id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='reserve' AND constraint_name='chk_reserve_lifecycle') THEN
    ALTER TABLE reserve ADD CONSTRAINT chk_reserve_lifecycle CHECK (
      state IN ('ACTIVE','REFUNDED','CONVERTED','EXPIRED')
      AND ((state='REFUNDED' AND reserve_flag='Y') OR (state<>'REFUNDED' AND reserve_flag='N'))
      AND (state='REFUNDED' OR bed_id IS NOT NULL));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='retreat_apply' AND column_name='pending_elder_id') THEN
    ALTER TABLE retreat_apply ADD COLUMN pending_elder_id BIGINT GENERATED ALWAYS AS (CASE WHEN apply_flag='待审核' THEN elder_id ELSE NULL END) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='retreat_apply' AND index_name='uk_retreat_pending_elder') THEN
    ALTER TABLE retreat_apply ADD UNIQUE KEY uk_retreat_pending_elder(pending_elder_id);
  END IF;
  SELECT id AS legacy_refunded_reserve_id,elder_id FROM reserve WHERE state='REFUNDED' AND bed_id IS NULL ORDER BY id;
END$$
DELIMITER ;
CALL upgrade_bed_lifecycle();
DROP PROCEDURE upgrade_bed_lifecycle;
