package io.github.opensabre.iqc.action;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Bounded read-only journal access after the owning source and ledger have been authorized. */
@Mapper
public interface ActionStateEventMapper {
    @Select("""
        SELECT e.version,e.previous_version,e.actor,e.recorded_at_utc,e.reconciliation_reason,
          JSON_OBJECT('status',JSON_EXTRACT(e.state_json,'$.status'),'attemptCount',JSON_EXTRACT(e.state_json,'$.attempt_count'),'claimedAtUtc',JSON_EXTRACT(e.state_json,'$.claimed_at_utc'),'settledAtUtc',JSON_EXTRACT(e.state_json,'$.settled_at_utc'),'nextAttemptAtUtc',JSON_EXTRACT(e.state_json,'$.next_attempt_at_utc'),'outcome',JSON_EXTRACT(e.state_json,'$.outcome'),'evidenceReference',JSON_EXTRACT(e.state_json,'$.evidence_reference'),'resultReference',JSON_EXTRACT(e.state_json,'$.result_reference'),'errorCode',JSON_EXTRACT(e.state_json,'$.last_error_code')) AS state_json,
          CASE WHEN e.previous_state_json IS NULL THEN NULL ELSE JSON_OBJECT('status',JSON_EXTRACT(e.previous_state_json,'$.status'),'attemptCount',JSON_EXTRACT(e.previous_state_json,'$.attempt_count'),'claimedAtUtc',JSON_EXTRACT(e.previous_state_json,'$.claimed_at_utc'),'settledAtUtc',JSON_EXTRACT(e.previous_state_json,'$.settled_at_utc'),'nextAttemptAtUtc',JSON_EXTRACT(e.previous_state_json,'$.next_attempt_at_utc'),'outcome',JSON_EXTRACT(e.previous_state_json,'$.outcome'),'evidenceReference',JSON_EXTRACT(e.previous_state_json,'$.evidence_reference'),'resultReference',JSON_EXTRACT(e.previous_state_json,'$.result_reference'),'errorCode',JSON_EXTRACT(e.previous_state_json,'$.last_error_code')) END AS previous_state_json
        FROM iqc_action_execution_event e
        WHERE e.action_execution_id=#{actionId} AND e.source_result_id=#{resultId}
          AND (#{beforeVersion} IS NULL OR e.version < #{beforeVersion})
        ORDER BY e.version DESC LIMIT 51
        """)
    List<ActionStateEventProjection> page(@Param("actionId") String actionId,@Param("resultId") String resultId,
                                         @Param("beforeVersion") Long beforeVersion);
}
