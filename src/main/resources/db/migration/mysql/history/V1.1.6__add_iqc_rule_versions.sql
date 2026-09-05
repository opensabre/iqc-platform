-- IQC 规则追加式版本历史。
ALTER TABLE `iqc_quality_rule`
    ADD COLUMN `version_no` int NOT NULL DEFAULT 1 AFTER `veto`;

CREATE TABLE `iqc_quality_rule_version` (
    `id` varchar(64) NOT NULL, `rule_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(128) NOT NULL, `rule_type` varchar(32) NOT NULL,
    `expression` text, `description` varchar(500) DEFAULT NULL, `deduction` int NOT NULL DEFAULT 10,
    `risk_level` varchar(32) NOT NULL DEFAULT 'MEDIUM', `veto` tinyint(1) NOT NULL DEFAULT 0, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_rule_version` (`rule_id`, `version_no`), KEY `idx_iqc_rule_version_status` (`rule_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
