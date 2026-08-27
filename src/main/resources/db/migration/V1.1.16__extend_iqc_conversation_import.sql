ALTER TABLE `iqc_conversation`
    ADD COLUMN `batch_no` varchar(64) DEFAULT NULL AFTER `id`,
    ADD COLUMN `source_type` varchar(32) NOT NULL DEFAULT 'FILE' AFTER `batch_no`,
    ADD COLUMN `external_id` varchar(128) DEFAULT NULL AFTER `source_type`,
    ADD INDEX `idx_iqc_conversation_batch` (`batch_no`, `created_time`),
    ADD INDEX `idx_iqc_conversation_external` (`external_id`);
