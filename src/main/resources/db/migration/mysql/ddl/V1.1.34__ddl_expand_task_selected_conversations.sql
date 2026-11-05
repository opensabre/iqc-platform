-- Execution-time FILTER can resolve more conversation IDs than the old batch TEXT payload permits.
ALTER TABLE `iqc_inspection_task` MODIFY COLUMN `conversation_ids_json` longtext;
