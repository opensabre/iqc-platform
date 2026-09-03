CREATE TABLE `iqc_conversation` (
 `id` varchar(64) NOT NULL, `source_file_name` varchar(255) NOT NULL, `source_fingerprint` varchar(128) NOT NULL,
 `message_count` int NOT NULL DEFAULT 0, `error_count` int NOT NULL DEFAULT 0, `ignored_blank_lines` int NOT NULL DEFAULT 0,
 `status` varchar(32) NOT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
 `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_conversation_fingerprint` (`source_fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_conversation_message` (
 `id` varchar(64) NOT NULL, `conversation_id` varchar(64) NOT NULL, `sequence_no` int NOT NULL,
 `speaker_role` varchar(32) NOT NULL, `relative_time` time NOT NULL, `content` text NOT NULL, `raw_line` text NOT NULL,
 `line_number` int NOT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
 `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), KEY `idx_iqc_conversation_message_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_inspection_task` (
 `id` varchar(64) NOT NULL, `conversation_id` varchar(64) NOT NULL, `name` varchar(255) NOT NULL,
 `agent_id` varchar(64) DEFAULT NULL, `rule_set_id` varchar(64) DEFAULT NULL, `agent_snapshot_json` text, `rule_snapshot_json` text,
 `status` varchar(32) NOT NULL, `total_messages` int NOT NULL DEFAULT 0, `processed_messages` int NOT NULL DEFAULT 0,
 `failed_messages` int NOT NULL DEFAULT 0, `current_execution_id` varchar(64) DEFAULT NULL, `attempt_count` int NOT NULL DEFAULT 0,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), KEY `idx_iqc_inspection_task_conversation` (`conversation_id`), KEY `idx_iqc_inspection_task_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_quality_agent` (
 `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL,
 `description` varchar(500) DEFAULT NULL, `status` varchar(32) NOT NULL, `config_json` text,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_quality_agent_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_quality_rule` (
 `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `rule_type` varchar(32) NOT NULL,
 `expression` text, `description` varchar(500) DEFAULT NULL, `status` varchar(32) NOT NULL,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_quality_rule_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_inspection_result` (
 `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL, `execution_id` varchar(64) DEFAULT NULL, `conversation_id` varchar(64) NOT NULL,
 `message_id` varchar(64) NOT NULL, `rule_id` varchar(64) DEFAULT NULL, `speaker_role` varchar(32) NOT NULL,
 `result_status` varchar(32) NOT NULL, `score` int NOT NULL DEFAULT 0, `reason` varchar(500) NOT NULL, `evidence` text,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), KEY `idx_iqc_inspection_result_task` (`task_id`), KEY `idx_iqc_inspection_result_status` (`result_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_task_execution` (
 `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL, `attempt_no` int NOT NULL, `status` varchar(32) NOT NULL,
 `processed_messages` int NOT NULL DEFAULT 0, `failed_messages` int NOT NULL DEFAULT 0, `error_message` varchar(1000) DEFAULT NULL,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_task_execution_attempt` (`task_id`, `attempt_no`), KEY `idx_iqc_task_execution_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_task_item` (
 `id` varchar(64) NOT NULL, `task_id` varchar(64) NOT NULL, `execution_id` varchar(64) NOT NULL,
 `message_id` varchar(64) NOT NULL, `sequence_no` int NOT NULL, `status` varchar(32) NOT NULL,
 `result_id` varchar(64) DEFAULT NULL, `attempt_count` int NOT NULL DEFAULT 0, `error_message` varchar(1000) DEFAULT NULL,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_task_item_execution_message` (`execution_id`, `message_id`), KEY `idx_iqc_task_item_status` (`execution_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
