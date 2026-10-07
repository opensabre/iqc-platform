ALTER TABLE iqc_model_gateway_call
 ADD COLUMN max_output_tokens INT NULL,
 ADD COLUMN output_token_parameter VARCHAR(32) NULL,
 ADD COLUMN output_limit_single_response BOOLEAN NULL,
 ADD COLUMN output_limit_status VARCHAR(48) NULL;
