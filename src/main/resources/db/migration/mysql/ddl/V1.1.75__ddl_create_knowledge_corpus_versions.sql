-- Independent reference corpora; no instruction, conversation or Gold duplication.
CREATE TABLE `iqc_knowledge_base` (
  `id` varchar(64) NOT NULL,
  `code` varchar(64) NOT NULL,
  `name` varchar(100) NOT NULL,
  `draft_config_json` longtext NOT NULL,
  `draft_revision` int NOT NULL DEFAULT 1,
  `active_published_version` int DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DISABLED',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_knowledge_code` (`code`),
  KEY `idx_iqc_knowledge_owner` (`created_by`, `owner_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `iqc_knowledge_base_version` (
  `id` varchar(64) NOT NULL,
  `knowledge_base_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `source_draft_revision` int NOT NULL,
  `snapshot_json` longtext NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_knowledge_version` (`knowledge_base_id`,`version_no`),
  UNIQUE KEY `uk_iqc_knowledge_revision` (`knowledge_base_id`,`source_draft_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
