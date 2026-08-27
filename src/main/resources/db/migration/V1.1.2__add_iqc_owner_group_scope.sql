-- IQC 组织数据范围：保存创建时的 OpenSabre groupId 快照。
ALTER TABLE `iqc_conversation`
    ADD COLUMN `owner_group_id` varchar(64) DEFAULT NULL AFTER `status`;

ALTER TABLE `iqc_inspection_task`
    ADD COLUMN `owner_group_id` varchar(64) DEFAULT NULL AFTER `attempt_count`;

CREATE INDEX `idx_iqc_conversation_owner_group` ON `iqc_conversation` (`owner_group_id`);
CREATE INDEX `idx_iqc_inspection_task_owner_group` ON `iqc_inspection_task` (`owner_group_id`);
