-- Observations are not execution attempts or queue entries. No historical requests are fabricated.
CREATE TABLE iqc_action_reconciliation_attempt (
 id CHAR(32) NOT NULL,
 action_execution_id VARCHAR(64) NOT NULL,
 source_result_id VARCHAR(64) NOT NULL,
 observed_version BIGINT NOT NULL,
 actor VARCHAR(128) NOT NULL,
 actor_user_id VARCHAR(128) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 state VARCHAR(32) NOT NULL,
 started_at_utc DATETIME(3) NOT NULL,
 finished_at_utc DATETIME(3) NULL,
 confirmed_version BIGINT NULL,
 PRIMARY KEY (id),
 KEY idx_iqc_reconciliation_action (action_execution_id,started_at_utc,id),
 CONSTRAINT chk_iqc_reconciliation_version CHECK (observed_version > 0),
 CONSTRAINT chk_iqc_reconciliation_time CHECK (finished_at_utc IS NULL OR finished_at_utc >= started_at_utc),
 CONSTRAINT chk_iqc_reconciliation_terminal CHECK (
  (state='STARTED' AND finished_at_utc IS NULL AND confirmed_version IS NULL) OR
  (state IN ('UNCONFIRMED','STALE_OBSERVATION','LOOKUP_FAILED') AND finished_at_utc IS NOT NULL AND confirmed_version IS NULL) OR
  (state='CONFIRMED' AND finished_at_utc IS NOT NULL AND confirmed_version IS NOT NULL
    AND observed_version < 9223372036854775807 AND confirmed_version=observed_version+1)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
