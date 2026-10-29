-- Dataset identity and immutable approved membership are distinct from the existing candidate sample store.
CREATE TABLE `iqc_evaluation_dataset` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `dataset_type` varchar(32) NOT NULL,
  `draft_config_json` longtext NOT NULL,
  `draft_revision` int NOT NULL DEFAULT 1,
  `active_version` int DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ACTIVE',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_dataset_owner` (`created_by`,`owner_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `iqc_evaluation_dataset_version` (
  `id` varchar(64) NOT NULL,
  `dataset_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `source_draft_revision` int NOT NULL,
  `sample_count` int NOT NULL,
  `snapshot_json` longtext NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_dataset_version` (`dataset_id`,`version_no`),
  UNIQUE KEY `uk_iqc_dataset_revision` (`dataset_id`,`source_draft_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
