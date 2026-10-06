-- A received trigger upgrades the same original Task; no parallel receipt/inspection main tables.
ALTER TABLE iqc_inspection_task
 ADD COLUMN trigger_source varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
 ADD COLUMN trigger_event_id varchar(128) COLLATE utf8mb4_bin DEFAULT NULL,
 ADD COLUMN trigger_payload_hash varchar(64) DEFAULT NULL,
 ADD COLUMN trigger_request_hash varchar(64) DEFAULT NULL,
 ADD COLUMN trigger_request_json longtext DEFAULT NULL,
 ADD COLUMN trigger_retry_not_before_utc datetime(3) DEFAULT NULL,
 ADD COLUMN trigger_error varchar(1000) DEFAULT NULL,
 ADD UNIQUE KEY uk_iqc_trigger_identity (created_by,trigger_source,trigger_event_id),
 ADD KEY idx_iqc_trigger_waiting (status,trigger_retry_not_before_utc);
