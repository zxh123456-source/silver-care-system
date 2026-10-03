-- AI 护理工作台一期迁移脚本
USE `db_beadhouse`;
CREATE TABLE IF NOT EXISTS `care_note` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint DEFAULT NULL COMMENT '护理员编号',
  `event_time` datetime NOT NULL COMMENT '事件时间',
  `source_text` varchar(2000) NOT NULL COMMENT '护理员原始记录',
  `observation` varchar(2000) DEFAULT NULL COMMENT '观察到的情况',
  `action_taken` varchar(2000) DEFAULT NULL COMMENT '已采取措施',
  `follow_up` varchar(2000) DEFAULT NULL COMMENT '待跟进事项',
  `appetite` varchar(500) DEFAULT NULL COMMENT '进餐情况',
  `sleep` varchar(500) DEFAULT NULL COMMENT '休息情况',
  `medicine` varchar(500) DEFAULT NULL COMMENT '服药情况',
  `activity` varchar(500) DEFAULT NULL COMMENT '活动情况',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '待跟进状态',
  `ai_generated` varchar(2) NOT NULL DEFAULT 'Y' COMMENT '是否由 AI 生成',
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_care_note_elder_time` (`elder_id`, `event_time`),
  KEY `idx_care_note_event_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI护理记录';

CREATE TABLE IF NOT EXISTS `policy_doc` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `title` varchar(200) NOT NULL COMMENT '制度标题',
  `source` varchar(255) NOT NULL COMMENT '制度来源',
  `section_no` int NOT NULL COMMENT '片段序号',
  `content` varchar(2000) NOT NULL COMMENT '制度片段',
  `keywords` varchar(2000) DEFAULT NULL COMMENT '检索关键词',
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_policy_doc_title` (`title`),
  KEY `idx_policy_doc_section` (`title`, `section_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='院内制度知识库片段';

CREATE TABLE IF NOT EXISTS `ai_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `module` varchar(50) NOT NULL COMMENT 'AI模块',
  `action` varchar(50) NOT NULL COMMENT '操作动作',
  `object_type` varchar(50) DEFAULT NULL COMMENT '对象类型',
  `object_id` bigint DEFAULT NULL COMMENT '对象编号',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人编号',
  `operator_name` varchar(100) DEFAULT NULL COMMENT '操作人姓名快照',
  `detail` varchar(500) DEFAULT NULL COMMENT '非敏感操作摘要',
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_audit_time` (`create_time`, `id`),
  KEY `idx_ai_audit_operator` (`operator_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI操作审计日志';

CREATE TABLE IF NOT EXISTS `medication_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `medicine_name` varchar(100) NOT NULL COMMENT '药品名称（按医嘱录入）',
  `dose_instruction` varchar(200) NOT NULL COMMENT '执行说明（按医嘱录入）',
  `periods` varchar(50) NOT NULL COMMENT '执行时段，逗号分隔',
  `start_date` date NOT NULL,
  `end_date` date DEFAULT NULL,
  `enabled` varchar(2) NOT NULL DEFAULT 'Y',
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_med_plan_elder_date` (`elder_id`, `enabled`, `start_date`, `end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用药执行计划';

CREATE TABLE IF NOT EXISTS `medication_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` bigint NOT NULL,
  `elder_id` bigint NOT NULL,
  `execution_date` date NOT NULL,
  `period` varchar(10) NOT NULL,
  `status` varchar(20) NOT NULL COMMENT 'DONE/SKIPPED',
  `executed_time` datetime NOT NULL,
  `note` varchar(500) DEFAULT NULL,
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_med_execution` (`plan_id`, `execution_date`, `period`),
  KEY `idx_med_execution_elder_date` (`elder_id`, `execution_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用药执行登记';

CREATE TABLE IF NOT EXISTS `policy_rag_sync` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_key` char(64) NOT NULL,
  `title` varchar(200) NOT NULL,
  `source` varchar(255) NOT NULL,
  `desired_action` varchar(10) NOT NULL COMMENT 'UPSERT/DELETE',
  `revision` char(64) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `attempt_count` int NOT NULL DEFAULT 0,
  `next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error_code` varchar(100) DEFAULT NULL,
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_rag_document` (`document_key`),
  KEY `idx_policy_rag_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='制度RAG索引期望状态';

CREATE TABLE IF NOT EXISTS `elder_staff_assignment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `elder_id` bigint NOT NULL,
  `staff_id` bigint NOT NULL,
  `active` varchar(2) NOT NULL DEFAULT 'Y',
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_elder_staff_assignment` (`elder_id`, `staff_id`),
  KEY `idx_assignment_staff_active` (`staff_id`, `active`, `elder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工老人数据范围分配';

CREATE TABLE IF NOT EXISTS `ai_metric_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `feature` varchar(40) NOT NULL,
  `stage` varchar(20) NOT NULL,
  `outcome` varchar(20) NOT NULL,
  `backend` varchar(80) DEFAULT NULL,
  `model_used` varchar(2) NOT NULL DEFAULT 'N',
  `grounded` varchar(2) DEFAULT NULL,
  `duration_ms` bigint NOT NULL DEFAULT 0,
  `input_count` int NOT NULL DEFAULT 0,
  `output_count` int NOT NULL DEFAULT 0,
  `prompt_tokens` int DEFAULT NULL,
  `completion_tokens` int DEFAULT NULL,
  `fallback_code` varchar(80) DEFAULT NULL,
  `error_code` varchar(80) DEFAULT NULL,
  `create_id` bigint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_id` bigint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_metric_feature_time` (`create_time`, `feature`, `stage`),
  KEY `idx_ai_metric_outcome_time` (`outcome`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI强类型指标事件';

-- 将原体检快照表扩展为可记录部分指标的日常历史表。
SET @measure_time_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'health_data' AND column_name = 'measure_time'
);
SET @measure_time_sql = IF(@measure_time_exists = 0,
  'ALTER TABLE health_data ADD COLUMN measure_time datetime NULL AFTER elder_id',
  'SELECT 1');
PREPARE measure_time_stmt FROM @measure_time_sql;
EXECUTE measure_time_stmt;
DEALLOCATE PREPARE measure_time_stmt;
SET @remarks_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'health_data' AND column_name = 'remarks'
);
SET @remarks_sql = IF(@remarks_exists = 0,
  'ALTER TABLE health_data ADD COLUMN remarks varchar(500) NULL AFTER moisture_content',
  'SELECT 1');
PREPARE remarks_stmt FROM @remarks_sql;
EXECUTE remarks_stmt;
DEALLOCATE PREPARE remarks_stmt;
ALTER TABLE `health_data`
  MODIFY `height` int NULL,
  MODIFY `weight` double NULL,
  MODIFY `temperature` double NULL,
  MODIFY `heart_rate` int NULL,
  MODIFY `systolic_blood_pressure` int NULL,
  MODIFY `diastolic_blood_pressure` int NULL,
  MODIFY `fasting_blood_glucose` decimal(6,2) NULL,
  MODIFY `postprandial_blood_glucose` decimal(6,2) NULL,
  MODIFY `blood_oxygen_saturation` int NULL,
  MODIFY `cholesterol` int NULL,
  MODIFY `uric_acid` int NULL,
  MODIFY `left_eye` double NULL,
  MODIFY `right_eye` double NULL,
  MODIFY `left_ear` varchar(5) NULL,
  MODIFY `right_ear` varchar(5) NULL,
  MODIFY `muscle_percentage` int NULL,
  MODIFY `body_fat_percentage` int NULL,
  MODIFY `waist_circumference` int NULL,
  MODIFY `hip_circumference` int NULL,
  MODIFY `moisture_content` int NULL;
UPDATE `health_data` SET `measure_time` = `create_time` WHERE `measure_time` IS NULL;
ALTER TABLE `health_data` MODIFY `measure_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP;
SET @health_idx_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'health_data' AND index_name = 'idx_health_elder_measure'
);
SET @health_idx_sql = IF(@health_idx_exists = 0,
  'CREATE INDEX idx_health_elder_measure ON health_data (elder_id, measure_time, id)',
  'SELECT 1');
PREPARE health_idx_stmt FROM @health_idx_sql;
EXECUTE health_idx_stmt;
DEALLOCATE PREPARE health_idx_stmt;

-- 如需显示在动态菜单中，执行下面两条，并给对应角色分配权限。
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (43, 0, 0x4149E68AA4E79086E5B7A5E4BD9CE58FB0, 'AICareManage', '/ai-care', NULL, '/ai-care')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (44, 43, 0xE68AA4E79086E8AEB0E5BD95E4B88EE4BAA4E78FAD, 'AICareWorkbench', '/workbench', NULL, '/ai/care/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (45, 43, 0xE999A2E58685E588B6E5BAA6E79FA5E8AF86E5BA93, 'AIPolicyWorkbench', '/policy', NULL, '/ai/policy/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (46, 43, 0xE581A5E5BAB7E8B68BE58ABFE8A782E5AF9F, 'AIHealthTrend', '/health', NULL, '/ai/health/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (47, 43, 0x4149E6938DE4BD9CE5AEA1E8AEA1, 'AIAuditLog', '/audit', NULL, '/ai/audit/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (48, 43, 0xE794A8E88DAFE689A7E8A18CE6A0B8E5AFB9, 'AIMedication', '/medication', NULL, '/ai/medication/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (49, 43, 0x4149E695B0E68DAEE69D83E99990, 'AIDataScope', '/scope', NULL, '/ai/scope/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);
INSERT INTO `auth` (`id`,`parent_id`,`title`,`name`,`path`,`icon`,`url`) VALUES
  (50, 43, 0xE6AF8FE697A5E68AA4E79086E58AA9E6898B, 'AIDailyAssistant', '/daily', NULL, '/ai/daily/index')
  ON DUPLICATE KEY UPDATE `title`=VALUES(`title`), `url`=VALUES(`url`);

-- 默认给管理员角色（role_id=1）分配菜单权限。
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 43, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=43);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 44, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=44);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 45, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=45);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 46, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=46);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 47, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=47);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 48, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=48);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 49, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=49);
INSERT INTO `role_auth` (`role_id`,`auth_id`,`create_id`,`create_time`,`update_id`,`update_time`)
SELECT 1, 50, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `role_auth` WHERE `role_id`=1 AND `auth_id`=50);
