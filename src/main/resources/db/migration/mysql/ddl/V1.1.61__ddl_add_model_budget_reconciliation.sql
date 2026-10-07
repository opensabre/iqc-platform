ALTER TABLE iqc_model_gateway_call
 ADD COLUMN budget_reconciled_tokens BIGINT NULL,
 ADD COLUMN budget_reconciled_cost DECIMAL(38,18) NULL,
 ADD COLUMN budget_reconciled_at_utc DATETIME(6) NULL,
 ADD COLUMN budget_reconciled_by VARCHAR(128) NULL,
 ADD COLUMN budget_reconciled_user_id VARCHAR(128) NULL,
 ADD COLUMN budget_reconciliation_evidence VARCHAR(128) NULL;
