-- Preserve the existing 8 MiB selector evidence and 1,000-conversation task contracts.
-- TEXT cannot hold those valid snapshots; keep both nullable for historical tasks.
ALTER TABLE `iqc_inspection_task`
    MODIFY COLUMN `selection_filter_json` MEDIUMTEXT NULL,
    MODIFY COLUMN `conversation_ids_json` MEDIUMTEXT NULL;
