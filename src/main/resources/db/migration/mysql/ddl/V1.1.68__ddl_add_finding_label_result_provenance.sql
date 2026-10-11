-- Extend the owning label result model; RULE and PIPELINE_FINDING never share a fake anchor.
ALTER TABLE iqc_inspection_label_result
 MODIFY COLUMN source_rule_result_id VARCHAR(64) NULL,
 ADD COLUMN source_finding_code VARCHAR(64) NULL,
 ADD COLUMN source_action_execution_id VARCHAR(64) NULL,
 ADD COLUMN source_input_hash CHAR(64) NULL,
 ADD COLUMN source_projection_hash CHAR(64) NULL,
 ADD INDEX idx_iqc_label_result_action (source_action_execution_id),
 ADD CONSTRAINT chk_iqc_label_result_source CHECK (
   (generation_source <> 'PIPELINE_FINDING' AND source_rule_result_id IS NOT NULL
    AND source_finding_code IS NULL AND source_action_execution_id IS NULL
    AND source_input_hash IS NULL AND source_projection_hash IS NULL)
   OR
   (generation_source = 'PIPELINE_FINDING' AND source_rule_result_id IS NULL AND value_json IS NOT NULL
    AND source_finding_code IS NOT NULL AND source_action_execution_id IS NOT NULL
    AND source_input_hash IS NOT NULL AND source_projection_hash IS NOT NULL)
 );
