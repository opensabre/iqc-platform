-- Independently reusable Periodic definitions and immutable revisions; tasks continue using the existing IQC task/execution tables.
CREATE TABLE `iqc_periodic_schedule` (
  `id` varchar(64) NOT NULL,
  `code` varchar(64) NOT NULL,
  `name` varchar(100) NOT NULL,
  `draft_config_json` longtext NOT NULL,
  `draft_revision` int NOT NULL DEFAULT 1,
  `active_revision_id` varchar(64) DEFAULT NULL,
  `next_planned_at_utc` datetime(3) DEFAULT NULL,
  `retry_not_before_utc` datetime(3) DEFAULT NULL,
  `last_error` varchar(1000) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DISABLED',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_periodic_code` (`code`),
  KEY `idx_iqc_periodic_owner` (`created_by`, `owner_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `iqc_periodic_schedule_revision` (
  `id` varchar(64) NOT NULL,
  `schedule_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `source_draft_revision` int NOT NULL,
  `snapshot_json` longtext NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_periodic_version` (`schedule_id`,`version_no`),
  UNIQUE KEY `uk_iqc_periodic_revision` (`schedule_id`,`source_draft_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Instances remain existing Tasks. UTC fields are separate from legacy local scheduled_time.
ALTER TABLE iqc_inspection_task
 ADD COLUMN periodic_schedule_id varchar(64) DEFAULT NULL,
 ADD COLUMN periodic_revision_id varchar(64) DEFAULT NULL,
 ADD COLUMN planned_at_utc datetime(3) DEFAULT NULL,
 ADD UNIQUE KEY uk_iqc_periodic_instance (periodic_schedule_id,periodic_revision_id,planned_at_utc);
CREATE INDEX idx_iqc_periodic_due ON iqc_periodic_schedule (status,next_planned_at_utc,retry_not_before_utc);
