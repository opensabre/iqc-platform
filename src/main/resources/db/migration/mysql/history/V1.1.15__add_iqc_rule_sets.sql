-- Versioned rule sets provide one stable selection for tasks and Agents.
CREATE TABLE `iqc_quality_rule_set` (
    `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL,
    `description` varchar(500) DEFAULT NULL, `rule_ids_json` text NOT NULL,
    `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ALL', `version_no` int NOT NULL DEFAULT 1,
    `status` varchar(32) NOT NULL, `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_rule_set_code` (`code`), KEY `idx_iqc_rule_set_status` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_quality_rule_set_version` (
    `id` varchar(64) NOT NULL, `rule_set_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `rule_ids_json` text NOT NULL, `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ALL', `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_rule_set_version` (`rule_set_id`, `version_no`),
    KEY `idx_iqc_rule_set_version_status` (`rule_set_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
