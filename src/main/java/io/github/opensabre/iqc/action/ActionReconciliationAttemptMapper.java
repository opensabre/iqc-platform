package io.github.opensabre.iqc.action;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

/** From-ledger observations with one-way terminal CAS; never schedules action execution. */
@Mapper
public interface ActionReconciliationAttemptMapper {
    @Insert("""
        INSERT INTO iqc_action_reconciliation_attempt
          (id,action_execution_id,source_result_id,observed_version,actor,actor_user_id,reason,state,started_at_utc)
        SELECT #{id},a.id,a.source_result_id,#{version},#{actor},#{userId},#{reason},'STARTED',#{now}
        FROM iqc_action_execution a
        WHERE BINARY a.id=BINARY #{actionId} AND BINARY a.source_result_id=BINARY #{resultId}
          AND BINARY a.owner=BINARY #{actor}
        """)
    int start(@Param("id") String id,@Param("actionId") String actionId,@Param("resultId") String resultId,
              @Param("version") long version,@Param("actor") String actor,@Param("userId") String userId,
              @Param("reason") String reason,@Param("now") LocalDateTime now);

    @Update("""
        UPDATE iqc_action_reconciliation_attempt SET state=#{state},finished_at_utc=#{now},confirmed_version=#{confirmedVersion}
        WHERE BINARY id=BINARY #{id} AND BINARY action_execution_id=BINARY #{actionId}
          AND BINARY source_result_id=BINARY #{resultId} AND observed_version=#{version}
          AND BINARY actor=BINARY #{actor} AND BINARY actor_user_id=BINARY #{userId}
          AND BINARY reason=BINARY #{reason} AND BINARY state='STARTED' AND started_at_utc<=#{now}
          AND (#{state}<>'CONFIRMED' OR EXISTS (
            SELECT 1 FROM iqc_action_execution_event e
            WHERE BINARY e.action_execution_id=BINARY #{actionId} AND e.version=#{confirmedVersion}
              AND e.previous_version=#{version} AND BINARY e.source_result_id=BINARY #{resultId}
              AND BINARY e.actor=BINARY #{actor} AND BINARY e.reconciliation_reason=BINARY #{reason}
              AND JSON_UNQUOTE(JSON_EXTRACT(e.state_json,'$.status'))='COMPLETE'
              AND JSON_UNQUOTE(JSON_EXTRACT(e.state_json,'$.outcome'))='CONFIRMED_SUCCESS'))
        """)
    int finish(@Param("id") String id,@Param("actionId") String actionId,@Param("resultId") String resultId,
               @Param("version") long version,@Param("actor") String actor,@Param("userId") String userId,
               @Param("reason") String reason,@Param("state") String state,
               @Param("confirmedVersion") Long confirmedVersion,@Param("now") LocalDateTime now);
}
