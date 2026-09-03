DROP INDEX `idx_iqc_conversation_external` ON `iqc_conversation`;
CREATE UNIQUE INDEX `uk_iqc_conversation_api_external`
    ON `iqc_conversation` (`source_type`, `external_id`);
