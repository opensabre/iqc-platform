ALTER TABLE `iqc_inspection_task`
    MODIFY COLUMN `conversation_id` varchar(64) DEFAULT NULL,
    ADD COLUMN `task_type` varchar(32) NOT NULL DEFAULT 'BATCH' AFTER `name`,
    ADD COLUMN `conversation_ids_json` text AFTER `conversation_id`,
    ADD COLUMN `selection_filter_json` text AFTER `conversation_ids_json`,
    ADD COLUMN `concurrency_limit` int NOT NULL DEFAULT 1 AFTER `selection_filter_json`,
    ADD COLUMN `scheduled_time` datetime DEFAULT NULL AFTER `concurrency_limit`;

ALTER TABLE `iqc_task_item`
    ADD COLUMN `conversation_id` varchar(64) DEFAULT NULL AFTER `execution_id`,
    ADD KEY `idx_iqc_task_item_conversation` (`task_id`, `conversation_id`);

CREATE INDEX `idx_iqc_inspection_task_schedule`
    ON `iqc_inspection_task` (`task_type`, `status`, `scheduled_time`);
