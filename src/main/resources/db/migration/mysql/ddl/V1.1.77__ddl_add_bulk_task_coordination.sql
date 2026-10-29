-- Reuse durable inspection tasks; ordinary tasks retain NULL coordination fields.
-- Progress is mutable coordination state, never part of the immutable production input snapshot.
ALTER TABLE iqc_inspection_task
 ADD COLUMN bulk_parent_task_id VARCHAR(64) NULL COMMENT 'Existing inspection task owning this bulk shard',
 ADD COLUMN bulk_shard_no INT NULL COMMENT 'Zero-based shard ordinal under the bulk parent',
 ADD COLUMN bulk_progress_json MEDIUMTEXT NULL COMMENT 'Internal versioned bulk coordinator checkpoint',
 ADD UNIQUE KEY uk_iqc_task_bulk_shard (bulk_parent_task_id,bulk_shard_no);
