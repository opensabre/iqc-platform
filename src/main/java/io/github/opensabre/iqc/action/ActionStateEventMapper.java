package io.github.opensabre.iqc.action;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** Bounded journal projection and exact recovery-reason attachment; callers authorize the owning source. */
@Mapper
public interface ActionStateEventMapper {
    @Select("""
        SELECT e.version,e.previous_version,e.actor,e.recorded_at_utc,e.reconciliation_reason,e.recovery_reason,
          JSON_OBJECT('status',JSON_EXTRACT(e.state_json,'$.status'),'attemptCount',JSON_EXTRACT(e.state_json,'$.attempt_count'),'claimedAtUtc',JSON_EXTRACT(e.state_json,'$.claimed_at_utc'),'settledAtUtc',JSON_EXTRACT(e.state_json,'$.settled_at_utc'),'nextAttemptAtUtc',JSON_EXTRACT(e.state_json,'$.next_attempt_at_utc'),'outcome',JSON_EXTRACT(e.state_json,'$.outcome'),'evidenceReference',JSON_EXTRACT(e.state_json,'$.evidence_reference'),'resultReference',JSON_EXTRACT(e.state_json,'$.result_reference'),'errorCode',JSON_EXTRACT(e.state_json,'$.last_error_code')) AS state_json,
          CASE WHEN e.previous_state_json IS NULL THEN NULL ELSE JSON_OBJECT('status',JSON_EXTRACT(e.previous_state_json,'$.status'),'attemptCount',JSON_EXTRACT(e.previous_state_json,'$.attempt_count'),'claimedAtUtc',JSON_EXTRACT(e.previous_state_json,'$.claimed_at_utc'),'settledAtUtc',JSON_EXTRACT(e.previous_state_json,'$.settled_at_utc'),'nextAttemptAtUtc',JSON_EXTRACT(e.previous_state_json,'$.next_attempt_at_utc'),'outcome',JSON_EXTRACT(e.previous_state_json,'$.outcome'),'evidenceReference',JSON_EXTRACT(e.previous_state_json,'$.evidence_reference'),'resultReference',JSON_EXTRACT(e.previous_state_json,'$.result_reference'),'errorCode',JSON_EXTRACT(e.previous_state_json,'$.last_error_code')) END AS previous_state_json
        FROM iqc_action_execution_event e
        WHERE e.action_execution_id=#{actionId} AND e.source_result_id=#{resultId}
          AND (#{beforeVersion} IS NULL OR e.version < #{beforeVersion})
        ORDER BY e.version DESC LIMIT 51
        """)
    List<ActionStateEventProjection> page(@Param("actionId") String actionId,@Param("resultId") String resultId,
                                         @Param("beforeVersion") Long beforeVersion);
    /** Attach once in the same transaction as recovery; never annotates an UNKNOWN resolution. */
    @Update("""
        UPDATE iqc_action_execution_event SET recovery_reason=#{reason}
        WHERE BINARY action_execution_id=BINARY #{actionId} AND version=#{version} AND previous_version=#{previousVersion}
          AND BINARY source_result_id=BINARY #{resultId} AND BINARY actor=BINARY #{actor}
          AND recovery_reason IS NULL AND CHAR_LENGTH(#{reason}) BETWEEN 1 AND 1000
          AND JSON_UNQUOTE(JSON_EXTRACT(previous_state_json,'$.status')) IN ('BLOCKED_SOURCE','BLOCKED_AUTHORIZATION','BLOCKED_ADAPTER')
          AND JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.status')) IN ('PENDING','WAITING_RETRY')
          AND (JSON_EXTRACT(previous_state_json,'$.outcome') <=> JSON_EXTRACT(state_json,'$.outcome'))
          AND (JSON_EXTRACT(previous_state_json,'$.attempt_count') <=> JSON_EXTRACT(state_json,'$.attempt_count'))
          AND (JSON_EXTRACT(previous_state_json,'$.claim_token') <=> JSON_EXTRACT(state_json,'$.claim_token'))
        """)
    int recordRecoveryReason(@Param("actionId") String actionId,@Param("version") long version,
            @Param("previousVersion") long previousVersion,@Param("resultId") String resultId,
            @Param("actor") String actor,@Param("reason") String reason);

}
