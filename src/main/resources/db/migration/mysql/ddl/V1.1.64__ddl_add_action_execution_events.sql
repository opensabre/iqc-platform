-- Transactional state journal, not a second queue. Existing rows are not backfilled.
CREATE TABLE iqc_action_execution_event (
 action_execution_id VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL,
 previous_version BIGINT NULL,
 source_result_id VARCHAR(64) NOT NULL,
 previous_state_json JSON NULL,
 state_json JSON NOT NULL,
 actor VARCHAR(128) NULL,
 recorded_at_utc DATETIME(3) NOT NULL,
 PRIMARY KEY (action_execution_id,version),
 KEY idx_iqc_action_event_result(source_result_id,action_execution_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
CREATE TRIGGER iqc_action_event_insert AFTER INSERT ON iqc_action_execution
FOR EACH ROW
BEGIN
 INSERT INTO iqc_action_execution_event
 (action_execution_id,version,previous_version,source_result_id,previous_state_json,state_json,actor,recorded_at_utc)
 VALUES (NEW.id,NEW.version,NULL,NEW.source_result_id,NULL,JSON_OBJECT('status',NEW.status,'attempt_count',NEW.attempt_count,'claim_token',NEW.claim_token,'claimed_at_utc',NEW.claimed_at_utc,'settled_at_utc',NEW.settled_at_utc,'next_attempt_at_utc',NEW.next_attempt_at_utc,'outcome',NEW.outcome,'evidence_reference',NEW.evidence_reference,'result_reference',NEW.result_reference,'last_error_code',NEW.last_error_code),NEW.created_by,UTC_TIMESTAMP(3));
END$$

CREATE TRIGGER iqc_action_event_update AFTER UPDATE ON iqc_action_execution
FOR EACH ROW
BEGIN
 IF NEW.version <> OLD.version OR NOT (NEW.status <=> OLD.status) OR
      NOT (NEW.attempt_count <=> OLD.attempt_count) OR
      NOT (NEW.claim_token <=> OLD.claim_token) OR
      NOT (NEW.claimed_at_utc <=> OLD.claimed_at_utc) OR
      NOT (NEW.settled_at_utc <=> OLD.settled_at_utc) OR
      NOT (NEW.next_attempt_at_utc <=> OLD.next_attempt_at_utc) OR
      NOT (NEW.outcome <=> OLD.outcome) OR
      NOT (NEW.evidence_reference <=> OLD.evidence_reference) OR
      NOT (NEW.result_reference <=> OLD.result_reference) OR
      NOT (NEW.last_error_code <=> OLD.last_error_code) THEN
  -- Never permit a state change to bypass the journal's version fence.
  IF OLD.version = 9223372036854775807 OR NEW.version <> OLD.version + 1 THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Action state changes require the next ledger version';
  END IF;
  INSERT INTO iqc_action_execution_event
  (action_execution_id,version,previous_version,source_result_id,previous_state_json,state_json,actor,recorded_at_utc)
  VALUES (NEW.id,NEW.version,OLD.version,NEW.source_result_id,JSON_OBJECT('status',OLD.status,'attempt_count',OLD.attempt_count,'claim_token',OLD.claim_token,'claimed_at_utc',OLD.claimed_at_utc,'settled_at_utc',OLD.settled_at_utc,'next_attempt_at_utc',OLD.next_attempt_at_utc,'outcome',OLD.outcome,'evidence_reference',OLD.evidence_reference,'result_reference',OLD.result_reference,'last_error_code',OLD.last_error_code),JSON_OBJECT('status',NEW.status,'attempt_count',NEW.attempt_count,'claim_token',NEW.claim_token,'claimed_at_utc',NEW.claimed_at_utc,'settled_at_utc',NEW.settled_at_utc,'next_attempt_at_utc',NEW.next_attempt_at_utc,'outcome',NEW.outcome,'evidence_reference',NEW.evidence_reference,'result_reference',NEW.result_reference,'last_error_code',NEW.last_error_code),NEW.updated_by,UTC_TIMESTAMP(3));
 END IF;
END$$
DELIMITER ;
