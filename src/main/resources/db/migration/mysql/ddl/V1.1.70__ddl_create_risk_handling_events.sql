-- Risk handling reasons commit in the same local transaction as the optimistic state change.
CREATE TABLE iqc_risk_handling_event (
 risk_event_id VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL,
 previous_status VARCHAR(24) NOT NULL,
 status VARCHAR(24) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 actor VARCHAR(128) NOT NULL,
 recorded_at_utc DATETIME(3) NOT NULL,
 PRIMARY KEY(risk_event_id,version),
 CONSTRAINT chk_iqc_risk_handling_version CHECK(version>0),
 CONSTRAINT chk_iqc_risk_handling_previous CHECK(previous_status IN ('OPEN','IN_PROGRESS','RESOLVED','DISMISSED')),
 CONSTRAINT chk_iqc_risk_handling_status CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','DISMISSED') AND status<>previous_status),
 CONSTRAINT chk_iqc_risk_handling_reason CHECK(CHAR_LENGTH(TRIM(reason))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TRIGGER trg_iqc_risk_handling_no_update BEFORE UPDATE ON iqc_risk_handling_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Risk handling events are immutable';
CREATE TRIGGER trg_iqc_risk_handling_no_delete BEFORE DELETE ON iqc_risk_handling_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Risk handling events are immutable';
