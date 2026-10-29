ALTER TABLE iqc_model_gateway_call
  ADD COLUMN request_data_encrypted LONGTEXT NULL COMMENT 'Optional encrypted model request diagnostics',
  ADD COLUMN response_data_encrypted LONGTEXT NULL COMMENT 'Optional encrypted model response diagnostics',
  ADD COLUMN call_data_status VARCHAR(32) NULL,
  ADD COLUMN call_data_recorded_at_utc DATETIME(3) NULL,
  ADD COLUMN call_data_expires_at_utc DATETIME(3) NULL,
  ADD INDEX idx_gateway_call_data_expiry (call_data_expires_at_utc);
