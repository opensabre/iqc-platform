-- Independent rollout revisions reuse the original pipeline asset lock and published version evidence.
CREATE TABLE iqc_pipeline_rollout_revision (
 id VARCHAR(64) NOT NULL,
 pipeline_id VARCHAR(64) NOT NULL,
 revision_no INT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 created_by VARCHAR(128) DEFAULT NULL,
 created_time DATETIME(3) DEFAULT NULL,
 updated_by VARCHAR(128) DEFAULT NULL,
 updated_time DATETIME(3) DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_iqc_pipeline_rollout_revision (pipeline_id, revision_no),
 CONSTRAINT chk_iqc_rollout_revision_positive CHECK (revision_no > 0),
 CONSTRAINT chk_iqc_rollout_snapshot_json CHECK (JSON_VALID(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TRIGGER trg_iqc_rollout_revision_no_update BEFORE UPDATE ON iqc_pipeline_rollout_revision
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Pipeline rollout revisions are immutable';
CREATE TRIGGER trg_iqc_rollout_revision_no_delete BEFORE DELETE ON iqc_pipeline_rollout_revision
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Pipeline rollout revisions are immutable';
