-- Extend existing definitions and attempts; no parallel scheduler or task persistence model.
ALTER TABLE iqc_inspection_task
    ADD COLUMN trigger_configuration_json LONGTEXT NULL COMMENT 'Server-owned trigger and captured IQC authorization',
    ADD COLUMN enabled TINYINT(1) NULL COMMENT 'Unified task trigger enabled; null for legacy tasks',
    ADD INDEX idx_iqc_task_unified_due (enabled, scheduled_time, status);
ALTER TABLE iqc_task_execution
    ADD COLUMN run_id VARCHAR(64) NULL COMMENT 'Logical run root; shared by retries and resumes',
    ADD INDEX idx_iqc_task_execution_run (task_id, run_id, attempt_no);
