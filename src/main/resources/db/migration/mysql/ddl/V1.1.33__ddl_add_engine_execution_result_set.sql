CREATE TABLE `iqc_engine_execution_result_set` (
    `id` varchar(64) NOT NULL,
    `task_id` varchar(64) NOT NULL,
    `execution_id` varchar(64) NOT NULL,
    `conversation_id` varchar(64) NOT NULL,
    `payload` longtext NOT NULL,
    `revision` int NOT NULL DEFAULT 0,
    `created_time` datetime(3) NOT NULL,
    `updated_time` datetime(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_iqc_engine_result_execution_conversation` (`execution_id`, `conversation_id`),
    KEY `idx_iqc_engine_result_task` (`task_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
