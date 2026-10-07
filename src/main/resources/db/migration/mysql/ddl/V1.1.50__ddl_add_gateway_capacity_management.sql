ALTER TABLE iqc_model_gateway_capacity
 ADD COLUMN policy_managed BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN policy_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE iqc_model_gateway_call
 ADD COLUMN capacity_released_at_utc DATETIME(3) NULL,
 ADD COLUMN capacity_released_by VARCHAR(128) NULL,
 ADD COLUMN capacity_released_user_id VARCHAR(64) NULL,
 ADD COLUMN capacity_release_reason VARCHAR(32) NULL,
 ADD COLUMN capacity_release_evidence VARCHAR(128) NULL;
