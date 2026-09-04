CREATE TABLE IF NOT EXISTS `iqc_result_feedback` (
    `id` varchar(64) NOT NULL, `result_id` varchar(64) NOT NULL, `feedback_type` varchar(32) NOT NULL,
    `comment` varchar(1000) DEFAULT NULL, `evidence_json` text, `status` varchar(32) NOT NULL DEFAULT 'OPEN',
    `owner_group_id` varchar(64) DEFAULT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), KEY `idx_iqc_feedback_result` (`result_id`, `created_time`),
    KEY `idx_iqc_feedback_type_status` (`feedback_type`, `status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_result_review` (
    `id` varchar(64) NOT NULL, `result_id` varchar(64) NOT NULL, `status` varchar(32) NOT NULL,
    `original_status` varchar(32) NOT NULL, `original_score` int DEFAULT NULL, `original_risk_level` varchar(32) DEFAULT NULL,
    `final_status` varchar(32) DEFAULT NULL, `final_score` int DEFAULT NULL, `final_risk_level` varchar(32) DEFAULT NULL,
    `review_comment` varchar(1000) DEFAULT NULL, `reviewer_id` varchar(128) DEFAULT NULL, `reviewed_time` datetime DEFAULT NULL,
    `owner_group_id` varchar(64) DEFAULT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_review_result` (`result_id`),
    KEY `idx_iqc_review_status_group` (`status`, `owner_group_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iqc_quality_sample` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `sample_type` varchar(32) NOT NULL,
    `source_result_id` varchar(64) DEFAULT NULL, `conversation_id` varchar(64) DEFAULT NULL, `message_id` varchar(64) DEFAULT NULL,
    `content_snapshot` text NOT NULL, `expected_json` text, `tags_json` text, `status` varchar(32) NOT NULL DEFAULT 'ENABLED',
    `owner_group_id` varchar(64) DEFAULT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), KEY `idx_iqc_sample_type_status` (`sample_type`, `status`, `created_time`),
    KEY `idx_iqc_sample_result` (`source_result_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
