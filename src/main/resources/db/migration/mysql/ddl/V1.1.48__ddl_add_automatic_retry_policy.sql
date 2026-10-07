ALTER TABLE iqc_inspection_task
 ADD COLUMN retry_policy_json LONGTEXT NULL,
 ADD COLUMN retry_policy_hash VARCHAR(64) NULL,
 ADD COLUMN automatic_retry_enabled BOOLEAN NULL,
 ADD COLUMN retry_not_before_utc DATETIME(3) NULL,
 ADD COLUMN retry_error VARCHAR(1000) NULL,
 ADD INDEX idx_iqc_automatic_retry(status,automatic_retry_enabled,retry_not_before_utc);
