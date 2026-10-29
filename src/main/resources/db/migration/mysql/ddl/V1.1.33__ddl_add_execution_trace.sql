-- Preserve raw node observations across rounds without duplicating the result aggregate.
-- Historical rows stay NULL; clients must not invent traces for pre-migration executions.
ALTER TABLE `iqc_inspection_conversation_result`
    ADD COLUMN `execution_trace_json` longtext DEFAULT NULL;
