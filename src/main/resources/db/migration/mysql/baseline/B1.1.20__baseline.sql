-- Generated from the complete verified migration history.
-- Regenerate with base-k8s/scripts/generate-flyway-baselines.sh; do not edit manually.

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `iqc_conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_conversation` (
  `id` varchar(64) NOT NULL,
  `batch_no` varchar(64) DEFAULT NULL,
  `source_type` varchar(32) NOT NULL DEFAULT 'FILE',
  `external_id` varchar(128) DEFAULT NULL,
  `employee_id` varchar(128) DEFAULT NULL,
  `employee_name` varchar(128) DEFAULT NULL,
  `employee_group_id` varchar(64) DEFAULT NULL,
  `customer_external_id` varchar(128) DEFAULT NULL,
  `customer_name` varchar(128) DEFAULT NULL,
  `customer_contact_masked` varchar(128) DEFAULT NULL,
  `channel` varchar(32) DEFAULT NULL,
  `started_time` datetime DEFAULT NULL,
  `ended_time` datetime DEFAULT NULL,
  `business_type` varchar(64) DEFAULT NULL,
  `business_no` varchar(128) DEFAULT NULL,
  `tags_json` text,
  `source_file_name` varchar(255) NOT NULL,
  `source_fingerprint` varchar(128) NOT NULL,
  `message_count` int NOT NULL DEFAULT '0',
  `error_count` int NOT NULL DEFAULT '0',
  `ignored_blank_lines` int NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL,
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_conversation_fingerprint` (`source_fingerprint`),
  UNIQUE KEY `uk_iqc_conversation_api_external` (`source_type`,`external_id`),
  KEY `idx_iqc_conversation_owner_group` (`owner_group_id`),
  KEY `idx_iqc_conversation_scope_created` (`owner_group_id`,`created_time`),
  KEY `idx_iqc_conversation_created` (`created_time`),
  KEY `idx_iqc_conversation_batch` (`batch_no`,`created_time`),
  KEY `idx_iqc_conversation_employee` (`employee_id`,`started_time`),
  KEY `idx_iqc_conversation_customer` (`customer_external_id`,`started_time`),
  KEY `idx_iqc_conversation_channel` (`channel`,`started_time`),
  KEY `idx_iqc_conversation_business` (`business_type`,`business_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_conversation_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_conversation_message` (
  `id` varchar(64) NOT NULL,
  `conversation_id` varchar(64) NOT NULL,
  `sequence_no` int NOT NULL,
  `speaker_role` varchar(32) NOT NULL,
  `relative_time` time NOT NULL,
  `content` text NOT NULL,
  `raw_line` text NOT NULL,
  `line_number` int NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_conversation_message_conversation` (`conversation_id`,`sequence_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_inspection_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_inspection_result` (
  `id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `execution_id` varchar(64) DEFAULT NULL,
  `conversation_id` varchar(64) NOT NULL,
  `message_id` varchar(64) NOT NULL,
  `rule_id` varchar(64) DEFAULT NULL,
  `speaker_role` varchar(32) NOT NULL,
  `result_status` varchar(32) NOT NULL,
  `score` int NOT NULL DEFAULT '0',
  `risk_level` varchar(32) DEFAULT NULL,
  `deduction` int NOT NULL DEFAULT '0',
  `reason` varchar(500) NOT NULL,
  `evidence` text,
  `finding_json` text,
  `evidence_json` text,
  `suggestion_json` text,
  `rule_breakdown_json` text,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_inspection_result_task` (`task_id`,`created_time`),
  KEY `idx_iqc_inspection_result_status` (`result_status`,`created_time`),
  KEY `idx_iqc_inspection_result_risk_created` (`risk_level`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_inspection_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_inspection_task` (
  `id` varchar(64) NOT NULL,
  `conversation_id` varchar(64) DEFAULT NULL,
  `conversation_ids_json` text,
  `selection_filter_json` text,
  `concurrency_limit` int NOT NULL DEFAULT '1',
  `scheduled_time` datetime DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `task_type` varchar(32) NOT NULL DEFAULT 'BATCH',
  `agent_id` varchar(64) DEFAULT NULL,
  `rule_set_id` varchar(64) DEFAULT NULL,
  `rule_ids_json` text,
  `agent_snapshot_json` text,
  `rule_snapshot_json` text,
  `status` varchar(32) NOT NULL,
  `total_messages` int NOT NULL DEFAULT '0',
  `processed_messages` int NOT NULL DEFAULT '0',
  `failed_messages` int NOT NULL DEFAULT '0',
  `current_execution_id` varchar(64) DEFAULT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_inspection_task_conversation` (`conversation_id`),
  KEY `idx_iqc_inspection_task_status` (`status`),
  KEY `idx_iqc_inspection_task_owner_group` (`owner_group_id`),
  KEY `idx_iqc_inspection_task_scope_created` (`owner_group_id`,`created_time`),
  KEY `idx_iqc_inspection_task_owner_created` (`created_by`,`created_time`),
  KEY `idx_iqc_inspection_task_agent_created` (`agent_id`,`created_time`),
  KEY `idx_iqc_inspection_task_schedule` (`task_type`,`status`,`scheduled_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_mcp_server`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_mcp_server` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(64) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `transport` varchar(32) NOT NULL,
  `endpoint` varchar(1000) NOT NULL,
  `auth_type` varchar(32) NOT NULL,
  `secret_ref` varchar(255) DEFAULT NULL,
  `timeout_seconds` int NOT NULL DEFAULT '30',
  `allowed_tools_json` text,
  `status` varchar(32) NOT NULL,
  `health_status` varchar(32) NOT NULL DEFAULT 'UNKNOWN',
  `version_no` int NOT NULL DEFAULT '1',
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_mcp_server_code` (`code`),
  KEY `idx_iqc_mcp_status_created` (`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_model_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_model_profile` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(64) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `provider` varchar(32) NOT NULL,
  `model_name` varchar(128) NOT NULL,
  `endpoint` varchar(1000) DEFAULT NULL,
  `secret_ref` varchar(255) DEFAULT NULL,
  `temperature` decimal(4,3) NOT NULL DEFAULT '0.100',
  `timeout_seconds` int NOT NULL DEFAULT '60',
  `max_retries` int NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL,
  `version_no` int NOT NULL DEFAULT '1',
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_model_profile_code` (`code`),
  KEY `idx_iqc_model_status_created` (`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_agent`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_agent` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `config_json` text,
  `version_no` int NOT NULL DEFAULT '1',
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_quality_agent_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_agent_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_agent_version` (
  `id` varchar(64) NOT NULL,
  `agent_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `config_json` text,
  `status` varchar(32) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_agent_version` (`agent_id`,`version_no`),
  KEY `idx_iqc_agent_version_status` (`agent_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_rule` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `category` varchar(64) NOT NULL DEFAULT 'CUSTOM',
  `rule_type` varchar(32) NOT NULL,
  `target_role` varchar(32) NOT NULL DEFAULT 'all',
  `expression` text,
  `description` varchar(500) DEFAULT NULL,
  `deduction` int NOT NULL DEFAULT '10',
  `risk_level` varchar(32) NOT NULL DEFAULT 'MEDIUM',
  `veto` tinyint(1) NOT NULL DEFAULT '0',
  `version_no` int NOT NULL DEFAULT '1',
  `status` varchar(32) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_quality_rule_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_rule_set`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_rule_set` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `rule_ids_json` text NOT NULL,
  `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ALL',
  `version_no` int NOT NULL DEFAULT '1',
  `status` varchar(32) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_rule_set_code` (`code`),
  KEY `idx_iqc_rule_set_status` (`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_rule_set_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_rule_set_version` (
  `id` varchar(64) NOT NULL,
  `rule_set_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `rule_ids_json` text NOT NULL,
  `aggregation_mode` varchar(16) NOT NULL DEFAULT 'ALL',
  `status` varchar(32) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_rule_set_version` (`rule_set_id`,`version_no`),
  KEY `idx_iqc_rule_set_version_status` (`rule_set_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_rule_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_rule_version` (
  `id` varchar(64) NOT NULL,
  `rule_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(128) NOT NULL,
  `category` varchar(64) NOT NULL DEFAULT 'CUSTOM',
  `rule_type` varchar(32) NOT NULL,
  `target_role` varchar(32) NOT NULL DEFAULT 'all',
  `expression` text,
  `description` varchar(500) DEFAULT NULL,
  `deduction` int NOT NULL DEFAULT '10',
  `risk_level` varchar(32) NOT NULL DEFAULT 'MEDIUM',
  `veto` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_rule_version` (`rule_id`,`version_no`),
  KEY `idx_iqc_rule_version_status` (`rule_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_quality_sample`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_quality_sample` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `sample_type` varchar(32) NOT NULL,
  `source_result_id` varchar(64) DEFAULT NULL,
  `conversation_id` varchar(64) DEFAULT NULL,
  `message_id` varchar(64) DEFAULT NULL,
  `content_snapshot` text NOT NULL,
  `expected_json` text,
  `tags_json` text,
  `status` varchar(32) NOT NULL DEFAULT 'ENABLED',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_sample_type_status` (`sample_type`,`status`,`created_time`),
  KEY `idx_iqc_sample_result` (`source_result_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_result_feedback`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_result_feedback` (
  `id` varchar(64) NOT NULL,
  `result_id` varchar(64) NOT NULL,
  `feedback_type` varchar(32) NOT NULL,
  `comment` varchar(1000) DEFAULT NULL,
  `evidence_json` text,
  `status` varchar(32) NOT NULL DEFAULT 'OPEN',
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_iqc_feedback_result` (`result_id`,`created_time`),
  KEY `idx_iqc_feedback_type_status` (`feedback_type`,`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_result_review`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_result_review` (
  `id` varchar(64) NOT NULL,
  `result_id` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `original_status` varchar(32) NOT NULL,
  `original_score` int DEFAULT NULL,
  `original_risk_level` varchar(32) DEFAULT NULL,
  `final_status` varchar(32) DEFAULT NULL,
  `final_score` int DEFAULT NULL,
  `final_risk_level` varchar(32) DEFAULT NULL,
  `review_comment` varchar(1000) DEFAULT NULL,
  `reviewer_id` varchar(128) DEFAULT NULL,
  `reviewed_time` datetime DEFAULT NULL,
  `owner_group_id` varchar(64) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_review_result` (`result_id`),
  KEY `idx_iqc_review_status_group` (`status`,`owner_group_id`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_skill`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_skill` (
  `id` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(64) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `instructions` text NOT NULL,
  `input_schema_json` text,
  `output_schema_json` text,
  `status` varchar(32) NOT NULL,
  `version_no` int NOT NULL DEFAULT '1',
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_skill_code` (`code`),
  KEY `idx_iqc_skill_status_created` (`status`,`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_skill_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_skill_version` (
  `id` varchar(64) NOT NULL,
  `skill_id` varchar(64) NOT NULL,
  `version_no` int NOT NULL,
  `name` varchar(128) NOT NULL,
  `code` varchar(64) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `instructions` text NOT NULL,
  `input_schema_json` text,
  `output_schema_json` text,
  `status` varchar(32) NOT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_skill_version` (`skill_id`,`version_no`),
  KEY `idx_iqc_skill_version_status` (`skill_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_task_execution`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_task_execution` (
  `id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `attempt_no` int NOT NULL,
  `status` varchar(32) NOT NULL,
  `processed_messages` int NOT NULL DEFAULT '0',
  `failed_messages` int NOT NULL DEFAULT '0',
  `error_message` varchar(1000) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_task_execution_attempt` (`task_id`,`attempt_no`),
  KEY `idx_iqc_task_execution_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

DROP TABLE IF EXISTS `iqc_task_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `iqc_task_item` (
  `id` varchar(64) NOT NULL,
  `task_id` varchar(64) NOT NULL,
  `execution_id` varchar(64) NOT NULL,
  `conversation_id` varchar(64) DEFAULT NULL,
  `message_id` varchar(64) NOT NULL,
  `sequence_no` int NOT NULL,
  `status` varchar(32) NOT NULL,
  `result_id` varchar(64) DEFAULT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `error_message` varchar(1000) DEFAULT NULL,
  `created_by` varchar(128) DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  `updated_by` varchar(128) DEFAULT NULL,
  `updated_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iqc_task_item_execution_message` (`execution_id`,`message_id`),
  KEY `idx_iqc_task_item_status` (`execution_id`,`status`),
  KEY `idx_iqc_task_item_conversation` (`task_id`,`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
