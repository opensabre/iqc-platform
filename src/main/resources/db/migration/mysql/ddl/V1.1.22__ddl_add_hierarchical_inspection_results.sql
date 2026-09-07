CREATE TABLE `iqc_inspection_conversation_result` (
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

CREATE TABLE `iqc_inspection_rule_result` (
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

CREATE TABLE `iqc_inspection_evidence` (
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
