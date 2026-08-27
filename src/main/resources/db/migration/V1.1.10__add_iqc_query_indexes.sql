-- IQC 列表分页和结果筛选索引，覆盖数据范围、时间排序和高频过滤条件。
CREATE INDEX `idx_iqc_conversation_scope_created` ON `iqc_conversation` (`owner_group_id`, `created_time`);
CREATE INDEX `idx_iqc_conversation_created` ON `iqc_conversation` (`created_time`);
ALTER TABLE `iqc_conversation_message`
    DROP INDEX `idx_iqc_conversation_message_conversation`,
    ADD INDEX `idx_iqc_conversation_message_conversation` (`conversation_id`, `sequence_no`);
CREATE INDEX `idx_iqc_inspection_task_scope_created` ON `iqc_inspection_task` (`owner_group_id`, `created_time`);
CREATE INDEX `idx_iqc_inspection_task_owner_created` ON `iqc_inspection_task` (`created_by`, `created_time`);
CREATE INDEX `idx_iqc_inspection_task_agent_created` ON `iqc_inspection_task` (`agent_id`, `created_time`);
ALTER TABLE `iqc_inspection_result`
    DROP INDEX `idx_iqc_inspection_result_task`,
    ADD INDEX `idx_iqc_inspection_result_task` (`task_id`, `created_time`),
    DROP INDEX `idx_iqc_inspection_result_status`,
    ADD INDEX `idx_iqc_inspection_result_status` (`result_status`, `created_time`);
CREATE INDEX `idx_iqc_inspection_result_risk_created` ON `iqc_inspection_result` (`risk_level`, `created_time`);
