-- Policy recalculations are append-only and separate from original machine results and human reviews.
CREATE TABLE `iqc_decision_revision` (
  `id` varchar(64) NOT NULL,
  `source_result_id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `source_hash` varchar(64) NOT NULL,
  `request_fingerprint` varchar(64) NOT NULL,
  `target_policy_json` longtext NOT NULL,
  `projection_json` longtext NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_decision_source` (`source_result_id`,`created_time`),
  KEY `idx_iqc_decision_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
