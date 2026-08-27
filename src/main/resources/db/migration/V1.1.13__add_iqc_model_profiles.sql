CREATE TABLE `iqc_model_profile` (
 `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
 `provider` varchar(32) NOT NULL, `model_name` varchar(128) NOT NULL, `endpoint` varchar(1000) DEFAULT NULL, `secret_ref` varchar(255) DEFAULT NULL,
 `temperature` decimal(4,3) NOT NULL DEFAULT 0.1, `timeout_seconds` int NOT NULL DEFAULT 60, `max_retries` int NOT NULL DEFAULT 0,
 `status` varchar(32) NOT NULL, `version_no` int NOT NULL DEFAULT 1,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_model_profile_code` (`code`), KEY `idx_iqc_model_status_created` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
