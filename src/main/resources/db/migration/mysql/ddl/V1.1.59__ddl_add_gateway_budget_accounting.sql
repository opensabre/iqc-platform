ALTER TABLE iqc_model_gateway_capacity
 ADD COLUMN token_budget_limit BIGINT NULL,
 ADD COLUMN token_budget_committed BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN cost_budget_limit DECIMAL(38,18) NULL,
 ADD COLUMN cost_budget_committed DECIMAL(38,18) NOT NULL DEFAULT 0,
 ADD COLUMN budget_currency VARCHAR(3) NULL,
 ADD COLUMN budget_context_window INT NULL,
 ADD COLUMN budget_deployment_ref VARCHAR(128) NULL,
 ADD COLUMN budget_evidence_ref VARCHAR(128) NULL,
 ADD COLUMN budget_output_parameter VARCHAR(32) NULL,
 ADD COLUMN budget_pricing_hash CHAR(64) NULL,
 ADD COLUMN budget_breached BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE iqc_model_gateway_call
 ADD COLUMN budget_reserved_tokens BIGINT NULL,
 ADD COLUMN budget_reserved_cost DECIMAL(38,18) NULL,
 ADD COLUMN budget_currency VARCHAR(3) NULL,
 ADD COLUMN budget_policy_version BIGINT NULL,
 ADD COLUMN budget_status VARCHAR(48) NULL,
 ADD COLUMN budget_evidence_ref VARCHAR(128) NULL;
