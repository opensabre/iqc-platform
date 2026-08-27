-- IQC Agent 追加式版本历史。
ALTER TABLE `iqc_quality_agent`
    ADD COLUMN `version_no` int NOT NULL DEFAULT 1 AFTER `config_json`;

CREATE TABLE `iqc_quality_agent_version` (
    `id` varchar(64) NOT NULL, `agent_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `description` varchar(500) DEFAULT NULL, `config_json` text, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_agent_version` (`agent_id`, `version_no`), KEY `idx_iqc_agent_version_status` (`agent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
