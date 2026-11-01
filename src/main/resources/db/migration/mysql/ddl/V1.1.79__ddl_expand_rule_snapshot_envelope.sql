-- Accepted production inputs may approach 16MiB before the task envelope is added.
-- Preserve the input guard; expand only storage for the complete frozen task.
ALTER TABLE `iqc_inspection_task`
    MODIFY COLUMN `rule_snapshot_json` LONGTEXT NULL;
