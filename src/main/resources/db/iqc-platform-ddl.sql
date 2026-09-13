CREATE TABLE IF NOT EXISTS `iqc_conversation` (
    `id` varchar(64) NOT NULL,
    `batch_no` varchar(64) DEFAULT NULL,
    `source_type` varchar(32) NOT NULL DEFAULT 'FILE',
    `external_id` varchar(128) DEFAULT NULL,
    `employee_id` varchar(128) DEFAULT NULL,
    `employee_name` varchar(128) DEFAULT NULL,
    `employee_group_id` varchar(64) DEFAULT NULL,
    `customer_external_id` varchar(128) DEFAULT NULL,
    `customer_name` varchar(128) DEFAULT NULL,
    `customer_contact_masked` varchar(128) DEFAULT NULL,
    `channel` varchar(32) DEFAULT NULL,
    `started_time` datetime DEFAULT NULL,
    `ended_time` datetime DEFAULT NULL,
    `business_type` varchar(64) DEFAULT NULL,
    `business_no` varchar(128) DEFAULT NULL,
    `tags_json` text,
    `source_file_name` varchar(255) NOT NULL,
    `source_fingerprint` varchar(128) NOT NULL,
    `message_count` int NOT NULL DEFAULT 0,
    `error_count` int NOT NULL DEFAULT 0,
    `ignored_blank_lines` int NOT NULL DEFAULT 0,
    `status` varchar(32) NOT NULL,
    `owner_group_id` varchar(64) DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL,
    `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_iqc_conversation_fingerprint` (`source_fingerprint`),
    KEY `idx_iqc_conversation_batch` (`batch_no`, `created_time`),
    UNIQUE KEY `uk_iqc_conversation_api_external` (`source_type`, `external_id`),
    KEY `idx_iqc_conversation_employee` (`employee_id`, `started_time`),
    KEY `idx_iqc_conversation_customer` (`customer_external_id`, `started_time`),
    KEY `idx_iqc_conversation_channel` (`channel`, `started_time`),
    KEY `idx_iqc_conversation_business` (`business_type`, `business_no`),
    KEY `idx_iqc_conversation_scope_created` (`owner_group_id`, `created_time`),
    KEY `idx_iqc_conversation_created` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Label insight domain and normalized result hierarchy (kept in sync with V1.1.22/V1.1.24).
CREATE TABLE IF NOT EXISTS `iqc_inspection_conversation_result` (
    `id` varchar(64) NOT NULL,
    `task_id` varchar(64) NOT NULL,
    `execution_id` varchar(64) NOT NULL,
    `conversation_id` varchar(64) NOT NULL,
    `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ANY',
    `result_status` varchar(32) NOT NULL,
    `score` int NOT NULL DEFAULT 0,
    `risk_level` varchar(32) NOT NULL DEFAULT 'LOW',
    `deduction` int NOT NULL DEFAULT 0,
    `reason` varchar(500) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL,
    `created_time` datetime(3) DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    `updated_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_iqc_conversation_result_execution` (`execution_id`, `conversation_id`),
    KEY `idx_iqc_conversation_result_task` (`task_id`, `created_time`),
    KEY `idx_iqc_conversation_result_status` (`result_status`, `risk_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_inspection_rule_result` (
    `id` varchar(64) NOT NULL,
    `conversation_result_id` varchar(64) NOT NULL,
    `rule_id` varchar(64) NOT NULL,
    `rule_version_no` int DEFAULT NULL,
    `rule_type` varchar(32) NOT NULL,
    `evaluation_scope` varchar(32) NOT NULL,
    `result_status` varchar(32) NOT NULL,
    `score` int NOT NULL DEFAULT 0,
    `risk_level` varchar(32) NOT NULL DEFAULT 'LOW',
    `deduction` int NOT NULL DEFAULT 0,
    `reason` varchar(500) NOT NULL,
    `confidence` decimal(5,4) DEFAULT NULL,
    `finding_json` mediumtext,
    `created_by` varchar(128) DEFAULT NULL,
    `created_time` datetime(3) DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    `updated_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_iqc_rule_result_conversation_rule` (`conversation_result_id`, `rule_id`),
    KEY `idx_iqc_rule_result_rule` (`rule_id`, `rule_version_no`),
    CONSTRAINT `fk_iqc_rule_result_conversation` FOREIGN KEY (`conversation_result_id`)
        REFERENCES `iqc_inspection_conversation_result` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_inspection_evidence` (
    `id` varchar(64) NOT NULL,
    `rule_result_id` varchar(64) NOT NULL,
    `message_id` varchar(64) NOT NULL,
    `sequence_no` int DEFAULT NULL,
    `internal_definition` varchar(256) DEFAULT NULL,
    `evidence_type` varchar(32) NOT NULL,
    `matched_text` text,
    `start_offset` int DEFAULT NULL,
    `end_offset` int DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL,
    `created_time` datetime(3) DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    `updated_time` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_iqc_evidence_rule_result` (`rule_result_id`),
    KEY `idx_iqc_evidence_message` (`message_id`),
    CONSTRAINT `fk_iqc_evidence_rule_result` FOREIGN KEY (`rule_result_id`)
        REFERENCES `iqc_inspection_rule_result` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_label_category` (
  `id` varchar(64) NOT NULL, `name` varchar(50) NOT NULL, `code` varchar(64) NOT NULL,
  `prompt` varchar(500) DEFAULT NULL, `max_child_count` int NOT NULL DEFAULT 0,
  `allow_auto_expand` tinyint(1) NOT NULL DEFAULT 0, `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `version_no` int NOT NULL DEFAULT 1, `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_category_code` (`code`),
  KEY `idx_iqc_label_category_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_label_group` (
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

CREATE TABLE IF NOT EXISTS `iqc_label` (
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

CREATE TABLE IF NOT EXISTS `iqc_label_value_definition` (
  `id` varchar(64) NOT NULL, `label_id` varchar(64) NOT NULL, `value_code` varchar(64) NOT NULL,
  `value_type` varchar(32) NOT NULL, `description` varchar(500) DEFAULT NULL,
  `display_order` int NOT NULL DEFAULT 0, `config_json` text,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_value` (`label_id`,`value_code`),
  KEY `idx_iqc_label_value_label` (`label_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_label_rule_binding` (
  `id` varchar(64) NOT NULL, `label_id` varchar(64) NOT NULL, `rule_id` varchar(64) NOT NULL,
  `rule_version_no` int NOT NULL, `binding_role` varchar(16) NOT NULL DEFAULT 'PRIMARY',
  `display_order` int NOT NULL DEFAULT 0, `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_label_rule_binding` (`label_id`,`rule_id`,`rule_version_no`),
  KEY `idx_iqc_label_binding_rule` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_label_collection` (
  `id` varchar(64) NOT NULL, `name` varchar(50) NOT NULL, `code` varchar(64) NOT NULL,
  `description` varchar(500) DEFAULT NULL, `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `version_no` int NOT NULL DEFAULT 1, `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL, `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_label_collection_code` (`code`),
  UNIQUE KEY `uk_iqc_label_collection_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_label_collection_member` (
  `id` varchar(64) NOT NULL, `collection_id` varchar(64) NOT NULL,
  `member_type` varchar(16) NOT NULL, `member_id` varchar(64) NOT NULL,
  `display_order` int NOT NULL DEFAULT 0, `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL, PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_label_collection_member` (`collection_id`,`member_type`,`member_id`),
  KEY `idx_iqc_label_collection_member` (`collection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_inspection_label_result` (
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

CREATE TABLE IF NOT EXISTS `iqc_label_candidate` (
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

CREATE TABLE IF NOT EXISTS `iqc_conversation_message` (
    `id` varchar(64) NOT NULL,
    `conversation_id` varchar(64) NOT NULL,
    `sequence_no` int NOT NULL,
    `speaker_role` varchar(32) NOT NULL,
    `relative_time` time NOT NULL,
    `content` text NOT NULL,
    `raw_line` text NOT NULL,
    `line_number` int NOT NULL,
    `created_by` varchar(128) DEFAULT NULL,
    `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_iqc_conversation_message_conversation` (`conversation_id`, `sequence_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_inspection_task` (
    `id` varchar(64) NOT NULL,
    `conversation_id` varchar(64) DEFAULT NULL,
    `name` varchar(255) NOT NULL,
    `task_type` varchar(32) NOT NULL DEFAULT 'BATCH',
    `conversation_ids_json` text,
    `selection_filter_json` text,
    `concurrency_limit` int NOT NULL DEFAULT 1,
    `scheduled_time` datetime DEFAULT NULL,
    `agent_id` varchar(64) DEFAULT NULL,
    `rule_set_id` varchar(64) DEFAULT NULL,
    `rule_ids_json` text,
    `agent_snapshot_json` text,
    `rule_snapshot_json` text,
    `label_scope_snapshot_json` mediumtext,
    `run_count` int NOT NULL DEFAULT 1,
    `confidence_threshold` decimal(5,4) DEFAULT NULL,
    `auto_expand_enabled` tinyint(1) NOT NULL DEFAULT 0,
    `auto_expand_prompt` varchar(1000) DEFAULT NULL,
    `queue_priority` bigint NOT NULL DEFAULT 0,
    `pause_requested` tinyint(1) NOT NULL DEFAULT 0,
    `cancel_requested` tinyint(1) NOT NULL DEFAULT 0,
    `status` varchar(32) NOT NULL,
    `total_messages` int NOT NULL DEFAULT 0,
    `processed_messages` int NOT NULL DEFAULT 0,
    `failed_messages` int NOT NULL DEFAULT 0,
    `current_execution_id` varchar(64) DEFAULT NULL,
    `attempt_count` int NOT NULL DEFAULT 0,
    `owner_group_id` varchar(64) DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL,
    `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_iqc_inspection_task_conversation` (`conversation_id`),
    KEY `idx_iqc_inspection_task_status` (`status`),
    KEY `idx_iqc_inspection_task_scope_created` (`owner_group_id`, `created_time`),
    KEY `idx_iqc_inspection_task_owner_created` (`created_by`, `created_time`),
    KEY `idx_iqc_inspection_task_agent_created` (`agent_id`, `created_time`),
    KEY `idx_iqc_inspection_task_schedule` (`task_type`, `status`, `scheduled_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_agent` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL,
    `description` varchar(500) DEFAULT NULL, `status` varchar(32) NOT NULL, `config_json` text, `version_no` int NOT NULL DEFAULT 1,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_quality_agent_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_agent_version` (
    `id` varchar(64) NOT NULL, `agent_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `description` varchar(500) DEFAULT NULL, `config_json` text, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_agent_version` (`agent_id`, `version_no`), KEY `idx_iqc_agent_version_status` (`agent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_skill` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL,
    `description` varchar(500) DEFAULT NULL, `instructions` text NOT NULL,
    `input_schema_json` text, `output_schema_json` text, `status` varchar(32) NOT NULL, `version_no` int NOT NULL DEFAULT 1,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_skill_code` (`code`), KEY `idx_iqc_skill_status_created` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_skill_version` (
    `id` varchar(64) NOT NULL, `skill_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `instructions` text NOT NULL, `input_schema_json` text, `output_schema_json` text, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_skill_version` (`skill_id`, `version_no`),
    KEY `idx_iqc_skill_version_status` (`skill_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_mcp_server` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `transport` varchar(32) NOT NULL, `endpoint` varchar(1000) NOT NULL, `auth_type` varchar(32) NOT NULL, `secret_ref` varchar(255) DEFAULT NULL,
    `timeout_seconds` int NOT NULL DEFAULT 30, `allowed_tools_json` text, `status` varchar(32) NOT NULL, `health_status` varchar(32) NOT NULL DEFAULT 'UNKNOWN', `version_no` int NOT NULL DEFAULT 1,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_mcp_server_code` (`code`), KEY `idx_iqc_mcp_status_created` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_model_profile` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `provider` varchar(32) NOT NULL, `model_name` varchar(128) NOT NULL, `endpoint` varchar(1000) DEFAULT NULL, `secret_ref` varchar(255) DEFAULT NULL,
    `temperature` decimal(4,3) NOT NULL DEFAULT 0.1, `timeout_seconds` int NOT NULL DEFAULT 60, `max_retries` int NOT NULL DEFAULT 0,
    `status` varchar(32) NOT NULL, `version_no` int NOT NULL DEFAULT 1,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_model_profile_code` (`code`), KEY `idx_iqc_model_status_created` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_rule` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `category` varchar(64) NOT NULL DEFAULT 'CUSTOM',
    `rule_type` varchar(32) NOT NULL, `target_role` varchar(32) NOT NULL DEFAULT 'all', `expression` text, `description` varchar(500) DEFAULT NULL, `deduction` int NOT NULL DEFAULT 10,
    `risk_level` varchar(32) NOT NULL DEFAULT 'MEDIUM', `veto` tinyint(1) NOT NULL DEFAULT 0, `version_no` int NOT NULL DEFAULT 1, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_quality_rule_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_rule_version` (
    `id` varchar(64) NOT NULL, `rule_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `category` varchar(64) NOT NULL DEFAULT 'CUSTOM', `rule_type` varchar(32) NOT NULL,
    `target_role` varchar(32) NOT NULL DEFAULT 'all', `expression` text, `description` varchar(500) DEFAULT NULL, `deduction` int NOT NULL DEFAULT 10,
    `risk_level` varchar(32) NOT NULL DEFAULT 'MEDIUM', `veto` tinyint(1) NOT NULL DEFAULT 0, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_rule_version` (`rule_id`, `version_no`), KEY `idx_iqc_rule_version_status` (`rule_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_rule_set` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL,
    `description` varchar(500) DEFAULT NULL, `rule_ids_json` text NOT NULL, `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ALL',
    `version_no` int NOT NULL DEFAULT 1, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_rule_set_code` (`code`), KEY `idx_iqc_rule_set_status` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_rule_set_version` (
    `id` varchar(64) NOT NULL, `rule_set_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `rule_ids_json` text NOT NULL, `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ALL', `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_rule_set_version` (`rule_set_id`, `version_no`), KEY `idx_iqc_rule_set_version_status` (`rule_set_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_inspection_result` (
    `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL, `execution_id` varchar(64) DEFAULT NULL, `conversation_id` varchar(64) NOT NULL,
    `message_id` varchar(64) NOT NULL, `rule_id` varchar(64) DEFAULT NULL, `speaker_role` varchar(32) NOT NULL,
    `result_status` varchar(32) NOT NULL, `score` int NOT NULL DEFAULT 0, `risk_level` varchar(32) DEFAULT NULL, `deduction` int NOT NULL DEFAULT 0,
    `reason` varchar(500) NOT NULL, `evidence` text, `finding_json` text, `evidence_json` text, `suggestion_json` text, `rule_breakdown_json` text,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), KEY `idx_iqc_inspection_result_task` (`task_id`, `created_time`), KEY `idx_iqc_inspection_result_status` (`result_status`, `created_time`),
    KEY `idx_iqc_inspection_result_risk` (`risk_level`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_task_execution` (
    `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL, `attempt_no` int NOT NULL,
    `status` varchar(32) NOT NULL, `processed_messages` int NOT NULL DEFAULT 0, `failed_messages` int NOT NULL DEFAULT 0,
    `error_message` varchar(1000) DEFAULT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_task_execution_attempt` (`task_id`, `attempt_no`), KEY `idx_iqc_task_execution_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_task_item` (
    `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL, `execution_id` varchar(64) NOT NULL, `conversation_id` varchar(64) DEFAULT NULL,
    `message_id` varchar(64) NOT NULL, `sequence_no` int NOT NULL, `status` varchar(32) NOT NULL,
    `result_id` varchar(64) DEFAULT NULL, `attempt_count` int NOT NULL DEFAULT 0, `error_message` varchar(1000) DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_task_item_execution_message` (`execution_id`, `message_id`), KEY `idx_iqc_task_item_status` (`execution_id`, `status`),
    KEY `idx_iqc_task_item_conversation` (`task_id`, `conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_result_feedback` (
    `id` varchar(64) NOT NULL, `result_id` varchar(64) NOT NULL, `feedback_type` varchar(32) NOT NULL, `comment` varchar(1000) DEFAULT NULL,
    `evidence_json` text, `status` varchar(32) NOT NULL DEFAULT 'OPEN', `owner_group_id` varchar(64) DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), KEY `idx_iqc_feedback_result` (`result_id`, `created_time`), KEY `idx_iqc_feedback_type_status` (`feedback_type`, `status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_result_review` (
    `id` varchar(64) NOT NULL, `result_id` varchar(64) NOT NULL, `status` varchar(32) NOT NULL, `original_status` varchar(32) NOT NULL,
    `original_score` int DEFAULT NULL, `original_risk_level` varchar(32) DEFAULT NULL, `final_status` varchar(32) DEFAULT NULL,
    `final_score` int DEFAULT NULL, `final_risk_level` varchar(32) DEFAULT NULL, `review_comment` varchar(1000) DEFAULT NULL,
    `reviewer_id` varchar(128) DEFAULT NULL, `reviewed_time` datetime DEFAULT NULL, `owner_group_id` varchar(64) DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_review_result` (`result_id`), KEY `idx_iqc_review_status_group` (`status`, `owner_group_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_sample` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `sample_type` varchar(32) NOT NULL, `source_result_id` varchar(64) DEFAULT NULL,
    `conversation_id` varchar(64) DEFAULT NULL, `message_id` varchar(64) DEFAULT NULL, `content_snapshot` text NOT NULL,
    `expected_json` text, `tags_json` text, `status` varchar(32) NOT NULL DEFAULT 'ENABLED', `owner_group_id` varchar(64) DEFAULT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), KEY `idx_iqc_sample_type_status` (`sample_type`, `status`, `created_time`), KEY `idx_iqc_sample_result` (`source_result_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
