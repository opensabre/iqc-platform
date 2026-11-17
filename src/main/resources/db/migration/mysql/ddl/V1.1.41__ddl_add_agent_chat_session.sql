-- Agent interactions are separate from imported customer-service inspection material.
CREATE TABLE `iqc_agent_chat_session` (
    `id` varchar(64) NOT NULL,
    `agent_id` varchar(64) NOT NULL,
    `version_no` int NOT NULL,
    `title` varchar(120) NOT NULL,
    `source` varchar(16) NOT NULL,
    `owner` varchar(128) NOT NULL,
    `owner_group_id` varchar(64) DEFAULT NULL,
    `config_hash` varchar(64) NOT NULL,
    `message_count` int NOT NULL,
    `messages_json` longtext NOT NULL,
    `last_message_time` datetime(3) NOT NULL,
    `created_time` datetime(3) NOT NULL,
    `updated_time` datetime(3) NOT NULL,
    `created_by` varchar(128) DEFAULT NULL,
    `updated_by` varchar(128) DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_iqc_agent_chat_agent_time` (`agent_id`, `last_message_time`, `id`),
    KEY `idx_iqc_agent_chat_owner` (`owner`, `agent_id`, `created_time`),
    KEY `idx_iqc_agent_chat_group` (`owner_group_id`, `agent_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
