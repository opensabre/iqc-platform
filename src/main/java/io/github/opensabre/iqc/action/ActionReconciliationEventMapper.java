package io.github.opensabre.iqc.action;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Write-once reason for the exact successful manual state transition, in its owning transaction. */
@Mapper
public interface ActionReconciliationEventMapper {
    @Update("""
        UPDATE iqc_action_execution_event SET reconciliation_reason=#{reason}
        WHERE action_execution_id=#{id} AND version=#{version} AND previous_version=#{previousVersion}
          AND source_result_id=#{resultId} AND actor=#{actor} AND reconciliation_reason IS NULL
          AND JSON_UNQUOTE(JSON_EXTRACT(previous_state_json,'$.status'))='REQUIRES_RECONCILIATION'
          AND JSON_UNQUOTE(JSON_EXTRACT(previous_state_json,'$.outcome'))='UNKNOWN'
          AND JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.status'))='COMPLETE'
          AND JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.outcome'))='CONFIRMED_SUCCESS'
        """)
    int recordReason(@Param("id") String id,@Param("version") long version,
                     @Param("previousVersion") long previousVersion,@Param("resultId") String resultId,
                     @Param("actor") String actor,@Param("reason") String reason);
}
