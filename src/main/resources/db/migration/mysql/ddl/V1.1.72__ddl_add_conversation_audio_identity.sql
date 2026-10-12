-- Immutable audio identity belongs to the existing conversation; legacy rows remain unbound.
ALTER TABLE iqc_conversation
    ADD COLUMN audio_sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN audio_size_bytes BIGINT NULL,
    ADD COLUMN audio_media_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN audio_duration_ms BIGINT NULL,
    ADD CONSTRAINT chk_iqc_conversation_audio_identity CHECK (
        (audio_sha256 IS NULL AND audio_size_bytes IS NULL AND audio_media_type IS NULL AND audio_duration_ms IS NULL)
        OR (audio_sha256 IS NOT NULL AND audio_size_bytes IS NOT NULL AND audio_media_type IS NOT NULL AND audio_duration_ms IS NOT NULL
            AND REGEXP_LIKE(audio_sha256, '^[0-9a-f]{64}$', 'c')
            AND audio_size_bytes > 0 AND audio_size_bytes <= 9007199254740991
            AND audio_duration_ms > 0 AND audio_duration_ms <= 9007199254740991
            AND audio_media_type IN ('audio/wav','audio/mpeg','audio/ogg','audio/flac','audio/mp4'))
    );
