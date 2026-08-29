CREATE TABLE IF NOT EXISTS `iqc_skill_version` (
    `id` varchar(64) NOT NULL, `skill_id` varchar(64) NOT NULL, `version_no` int NOT NULL,
    `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
    `instructions` text NOT NULL, `input_schema_json` text, `output_schema_json` text, `status` varchar(32) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
    PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_skill_version` (`skill_id`, `version_no`),
    KEY `idx_iqc_skill_version_status` (`skill_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `iqc_skill_version` (
    `id`, `skill_id`, `version_no`, `name`, `code`, `description`, `instructions`,
    `input_schema_json`, `output_schema_json`, `status`, `created_by`, `created_time`,
    `updated_by`, `updated_time`
)
SELECT UUID(), s.`id`, COALESCE(s.`version_no`, 1), s.`name`, s.`code`, s.`description`, s.`instructions`,
       s.`input_schema_json`, s.`output_schema_json`, s.`status`, s.`created_by`, s.`created_time`,
       s.`updated_by`, s.`updated_time`
FROM `iqc_skill` s
WHERE NOT EXISTS (
    SELECT 1 FROM `iqc_skill_version` v
    WHERE v.`skill_id` = s.`id` AND v.`version_no` = COALESCE(s.`version_no`, 1)
);
