CREATE TABLE `iqc_inspection_scheme` (
  `id` varchar(64) NOT NULL,
  `name` varchar(100) NOT NULL,
  `code` varchar(64) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `business_scene` varchar(100) NOT NULL,
  `draft_config_json` longtext NOT NULL,
  `draft_revision` int NOT NULL DEFAULT 1,
  `active_published_version` int DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_inspection_scheme_code` (`code`),
  KEY `idx_iqc_scheme_owner` (`created_by`, `owner_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `iqc_inspection_scheme_version` (
  `id` varchar(64) NOT NULL,
  `scheme_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `source_draft_revision` int NOT NULL,
  `snapshot_json` longtext NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_scheme_version` (`scheme_id`, `version_no`),
  UNIQUE KEY `uk_iqc_scheme_release_revision` (`scheme_id`, `source_draft_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
