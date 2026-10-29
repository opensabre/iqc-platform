ALTER TABLE iqc_model_gateway_call
 ADD COLUMN task_id VARCHAR(64) NULL,
 ADD COLUMN execution_id VARCHAR(64) NULL,
 ADD COLUMN node_id VARCHAR(256) NULL,
 ADD COLUMN run_index INT NULL,
 ADD COLUMN owner_group_id VARCHAR(64) NULL,
 ADD COLUMN conversation_id VARCHAR(64) NULL,
 ADD INDEX idx_iqc_gateway_task_run(task_id,execution_id,reserved_at_utc,call_id);
