-- Old rows retain their integer scores. V2 detector rows deliberately carry no score.
ALTER TABLE `iqc_inspection_result` MODIFY COLUMN `score` int DEFAULT NULL;
ALTER TABLE `iqc_inspection_rule_result` MODIFY COLUMN `score` int DEFAULT NULL;
ALTER TABLE `iqc_inspection_conversation_result` MODIFY COLUMN `score` int DEFAULT NULL;
ALTER TABLE `iqc_inspection_conversation_result` ADD COLUMN `final_score` decimal(6,2) DEFAULT NULL;
ALTER TABLE `iqc_inspection_conversation_result` ADD COLUMN `score_status` varchar(32) DEFAULT NULL;
ALTER TABLE `iqc_inspection_conversation_result` ADD COLUMN `scoring_result_json` longtext;
ALTER TABLE `iqc_inspection_conversation_result` ADD COLUMN `business_item_results_json` longtext;
