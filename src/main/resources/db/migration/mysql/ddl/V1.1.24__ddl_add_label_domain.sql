CREATE TABLE `iqc_label_category` (
  `id` varchar(64) NOT NULL, `name` varchar(50) NOT NULL, `code` varchar(64) NOT NULL,
  `prompt` varchar(500) DEFAULT NULL, `max_child_count` int NOT NULL DEFAULT 0,
  `allow_auto_expand` tinyint(1) NOT NULL DEFAULT 0, `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `version_no` int NOT NULL DEFAULT 1, `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_category_code` (`code`),
  KEY `idx_iqc_label_category_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label_group` (
  `id` varchar(64) NOT NULL, `category_id` varchar(64) NOT NULL, `name` varchar(50) NOT NULL,
  `code` varchar(64) NOT NULL, `description` varchar(200) DEFAULT NULL,
  `max_child_count` int NOT NULL DEFAULT 0, `allow_auto_expand` tinyint(1) NOT NULL DEFAULT 0,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT', `version_no` int NOT NULL DEFAULT 1,
  `owner_group_id` varchar(64) DEFAULT NULL, `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_label_group_code` (`code`),
  UNIQUE KEY `uk_iqc_label_group_name` (`category_id`,`name`), KEY `idx_iqc_label_group_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label` (
  `id` varchar(64) NOT NULL, `group_id` varchar(64) NOT NULL, `name` varchar(50) NOT NULL,
  `code` varchar(128) NOT NULL, `description` varchar(500) DEFAULT NULL,
  `target_role` varchar(32) NOT NULL DEFAULT 'all', `weight` decimal(6,2) NOT NULL DEFAULT 1.00,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT', `version_no` int NOT NULL DEFAULT 1,
  `source_type` varchar(32) NOT NULL DEFAULT 'MANUAL', `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_code` (`code`),
  UNIQUE KEY `uk_iqc_label_name` (`group_id`,`name`), KEY `idx_iqc_label_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label_value_definition` (
  `id` varchar(64) NOT NULL, `label_id` varchar(64) NOT NULL, `value_code` varchar(64) NOT NULL,
  `value_type` varchar(32) NOT NULL, `description` varchar(500) DEFAULT NULL,
  `display_order` int NOT NULL DEFAULT 0, `config_json` text,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_value` (`label_id`,`value_code`),
  KEY `idx_iqc_label_value_label` (`label_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label_rule_binding` (
  `id` varchar(64) NOT NULL, `label_id` varchar(64) NOT NULL, `rule_id` varchar(64) NOT NULL,
  `rule_version_no` int NOT NULL, `binding_role` varchar(16) NOT NULL DEFAULT 'PRIMARY',
  `display_order` int NOT NULL DEFAULT 0, `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_label_rule_binding` (`label_id`,`rule_id`,`rule_version_no`),
  KEY `idx_iqc_label_binding_rule` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label_collection` (
  `id` varchar(64) NOT NULL, `name` varchar(50) NOT NULL, `code` varchar(64) NOT NULL,
  `description` varchar(500) DEFAULT NULL, `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `version_no` int NOT NULL DEFAULT 1, `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_collection_code` (`code`),
  UNIQUE KEY `uk_iqc_label_collection_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label_collection_member` (
  `id` varchar(64) NOT NULL, `collection_id` varchar(64) NOT NULL,
  `member_type` varchar(16) NOT NULL, `member_id` varchar(64) NOT NULL,
  `display_order` int NOT NULL DEFAULT 0, `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_label_collection_member` (`collection_id`,`member_type`,`member_id`),
  KEY `idx_iqc_label_collection_member` (`collection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_inspection_label_result` (
  `id` varchar(64) NOT NULL, `conversation_result_id` varchar(64) NOT NULL,
  `label_id` varchar(64) NOT NULL, `label_version_no` int NOT NULL,
  `value_code` varchar(64) NOT NULL DEFAULT '', `value_json` text,
  `confidence` decimal(5,4) DEFAULT NULL, `source_rule_result_id` varchar(64) NOT NULL,
  `generation_source` varchar(32) NOT NULL DEFAULT 'RULE', `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_label_result` (`conversation_result_id`,`label_id`,`value_code`),
  KEY `idx_iqc_label_result_label` (`label_id`,`created_time`),
  KEY `idx_iqc_label_result_rule` (`source_rule_result_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_label_candidate` (
  `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL,
  `conversation_id` varchar(64) NOT NULL, `category_id` varchar(64) DEFAULT NULL,
  `group_id` varchar(64) DEFAULT NULL, `suggested_name` varchar(50) NOT NULL,
  `suggested_code` varchar(128) NOT NULL, `description` varchar(500) DEFAULT NULL,
  `value_json` text, `evidence_json` text, `confidence` decimal(5,4) NOT NULL,
  `model_snapshot_json` mediumtext, `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `merged_label_id` varchar(64) DEFAULT NULL, `created_label_id` varchar(64) DEFAULT NULL,
  `review_comment` varchar(500) DEFAULT NULL, `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), KEY `idx_iqc_label_candidate_task` (`task_id`,`status`),
  KEY `idx_iqc_label_candidate_similarity` (`suggested_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `iqc_inspection_task`
  ADD COLUMN `label_scope_snapshot_json` mediumtext NULL AFTER `rule_snapshot_json`,
  ADD COLUMN `run_count` int NOT NULL DEFAULT 1 AFTER `label_scope_snapshot_json`,
  ADD COLUMN `confidence_threshold` decimal(5,4) NULL AFTER `run_count`,
  ADD COLUMN `auto_expand_enabled` tinyint(1) NOT NULL DEFAULT 0 AFTER `confidence_threshold`,
  ADD COLUMN `auto_expand_prompt` varchar(1000) NULL AFTER `auto_expand_enabled`,
  ADD COLUMN `queue_priority` bigint NOT NULL DEFAULT 0 AFTER `auto_expand_prompt`,
  ADD COLUMN `pause_requested` tinyint(1) NOT NULL DEFAULT 0 AFTER `queue_priority`,
  ADD COLUMN `cancel_requested` tinyint(1) NOT NULL DEFAULT 0 AFTER `pause_requested`;

ALTER TABLE `iqc_inspection_rule_result`
  ADD COLUMN `confidence` decimal(5,4) NULL AFTER `reason`,
  ADD COLUMN `finding_json` mediumtext NULL AFTER `confidence`;
