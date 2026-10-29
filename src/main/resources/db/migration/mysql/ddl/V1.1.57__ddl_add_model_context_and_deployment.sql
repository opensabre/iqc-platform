ALTER TABLE iqc_model_profile
 ADD COLUMN context_window_tokens INT NULL,
 ADD COLUMN deployment_ref VARCHAR(128) NULL;
ALTER TABLE iqc_model_gateway_call
 ADD COLUMN context_window_tokens INT NULL,
 ADD COLUMN deployment_ref VARCHAR(128) NULL;
