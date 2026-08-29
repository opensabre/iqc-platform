CREATE TABLE IF NOT EXISTS `iqc_skill_version` (
    `id` varchar(64) NOT NULL, `skill_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `instructions` text NOT NULL, `input_schema_json` text, `output_schema_json` text, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_skill_version` (`skill_id`, `version_no`),
    KEY `idx_iqc_skill_version_status` (`skill_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
