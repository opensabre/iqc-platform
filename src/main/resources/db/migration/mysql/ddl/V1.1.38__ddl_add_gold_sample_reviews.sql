-- Reuse the existing candidate sample; ENABLED is not an approval.
ALTER TABLE `iqc_quality_sample`
  ADD COLUMN `gold_draft_json` longtext DEFAULT NULL,
  ADD COLUMN `gold_draft_revision` int NOT NULL DEFAULT 0,
  ADD COLUMN `gold_status` varchar(32) NOT NULL DEFAULT 'UNLABELLED',
  ADD COLUMN `gold_review_version` int NOT NULL DEFAULT 0;

CREATE TABLE `iqc_quality_sample_version` (
  `id` varchar(64) NOT NULL,
  `sample_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `source_draft_revision` int NOT NULL,
  `decision` varchar(32) NOT NULL,
  `reviewer_id` varchar(128) NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `snapshot_json` longtext NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime(3) DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_gold_version` (`sample_id`,`version_no`),
  UNIQUE KEY `uk_iqc_gold_revision` (`sample_id`,`source_draft_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
