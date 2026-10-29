ALTER TABLE iqc_model_gateway_call
 ADD COLUMN requested_model_name VARCHAR(256) NULL,
 ADD COLUMN model_profile_id VARCHAR(64) NULL,
 ADD COLUMN model_profile_version INT NULL,
 ADD COLUMN pricing_json TEXT NULL,
 ADD COLUMN pricing_hash VARCHAR(64) NULL,
 ADD COLUMN billing_supported BOOLEAN NULL,
 ADD COLUMN pricing_currency VARCHAR(3) NULL,
 ADD COLUMN calculated_cost DECIMAL(38,18) NULL,
 ADD COLUMN cost_status VARCHAR(48) NULL,
 ADD COLUMN cache_read_input_tokens BIGINT NULL,
 ADD COLUMN cache_write_input_tokens BIGINT NULL;
