-- Optional alignment on the original message model; legacy text rows remain unknown.
ALTER TABLE iqc_conversation_message
 ADD COLUMN start_ms BIGINT NULL,
 ADD COLUMN end_ms BIGINT NULL,
 ADD COLUMN confidence DECIMAL(7,6) NULL,
 ADD CONSTRAINT chk_iqc_message_alignment CHECK ((start_ms IS NULL AND end_ms IS NULL) OR (start_ms IS NOT NULL AND end_ms IS NOT NULL AND start_ms>=0 AND end_ms>=start_ms AND end_ms<=9007199254740991)),
 ADD CONSTRAINT chk_iqc_message_confidence CHECK (confidence IS NULL OR (confidence>=0 AND confidence<=1));
