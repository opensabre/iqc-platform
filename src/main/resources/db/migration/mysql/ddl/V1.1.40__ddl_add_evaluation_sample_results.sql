-- Keep evaluated machine outputs out of production inspection results and statistics.
CREATE TABLE `iqc_evaluation_sample_result` (
  `id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `execution_id` varchar(64) NOT NULL,
  `dataset_id` varchar(64) NOT NULL,
  `dataset_version` int NOT NULL,
  `dataset_hash` varchar(64) NOT NULL,
  `sample_id` varchar(64) NOT NULL,
  `sample_version` int NOT NULL,
  `gold_hash` varchar(64) NOT NULL,
  `conversation_id` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `actual_json` longtext NOT NULL,
  `actual_hash` varchar(64) NOT NULL,
  `duration_ms` bigint NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_evaluation_sample_run` (`task_id`,`execution_id`,`sample_id`),
  KEY `idx_iqc_evaluation_execution` (`execution_id`,`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
