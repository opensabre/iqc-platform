CREATE TABLE `iqc_mcp_server` (
 `id` varchar(64) NOT NULL, `name` varchar(128) NOT NULL, `code` varchar(64) NOT NULL, `description` varchar(500) DEFAULT NULL,
 `transport` varchar(32) NOT NULL, `endpoint` varchar(1000) NOT NULL, `auth_type` varchar(32) NOT NULL, `secret_ref` varchar(255) DEFAULT NULL,
 `timeout_seconds` int NOT NULL DEFAULT 30, `allowed_tools_json` text, `status` varchar(32) NOT NULL, `health_status` varchar(32) NOT NULL DEFAULT 'UNKNOWN', `version_no` int NOT NULL DEFAULT 1,
 `created_by` varchar(128) DEFAULT NULL, `created_time` datetime DEFAULT NULL, `updated_by` varchar(128) DEFAULT NULL, `updated_time` datetime DEFAULT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_iqc_mcp_server_code` (`code`), KEY `idx_iqc_mcp_status_created` (`status`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
