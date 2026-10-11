-- Business risk artifact, referencing the original result and immutable action input.
CREATE TABLE iqc_risk_event (
 id VARCHAR(64) NOT NULL,
 source_action_execution_id CHAR(64) NOT NULL,
 source_result_id VARCHAR(64) NOT NULL,
 source_input_hash CHAR(64) NOT NULL,
 category VARCHAR(64) NOT NULL,
 title VARCHAR(128) NOT NULL,
 description VARCHAR(1000) NOT NULL,
 risk_level VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
 version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(128) NOT NULL,
 created_time DATETIME(3) NULL,
 updated_by VARCHAR(128) NULL,
 updated_time DATETIME(3) NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_iqc_risk_action(source_action_execution_id),
 KEY idx_iqc_risk_result(source_result_id,id),
 KEY idx_iqc_risk_owner(created_by,status,created_time,id),
 CONSTRAINT chk_iqc_risk_status CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','DISMISSED')),
 CONSTRAINT chk_iqc_risk_level CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
 CONSTRAINT chk_iqc_risk_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
