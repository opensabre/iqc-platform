-- Completion is not BasePo.updated_time; historical timestamps are deliberately not inferred.
ALTER TABLE iqc_task_execution
 ADD COLUMN completed_at_utc DATETIME(3) NULL COMMENT 'First measured terminal transition UTC',
 ADD KEY idx_iqc_execution_completed_utc (completed_at_utc,status,task_id);
