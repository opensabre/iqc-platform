package io.github.opensabre.iqc.action;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** Reuses the persisted result as discovery checkpoint; not another queue or result model. */
@Mapper
public interface ActionDiscoveryMapper {
    record Source(String resultId,String taskId,String executionId,String conversationId,String owner) { }
    @Select("""
        SELECT r.id AS result_id,r.task_id,r.execution_id,r.conversation_id,t.created_by AS owner
        FROM iqc_inspection_conversation_result r
        JOIN iqc_inspection_task t ON t.id=r.task_id AND t.current_execution_id=r.execution_id
        WHERE r.aggregation_mode='PIPELINE' AND t.task_type='PIPELINE'
          AND t.status IN ('RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED')
          AND COALESCE(t.pause_requested,0)=0 AND COALESCE(t.cancel_requested,0)=0
          AND r.action_discovered_at_utc IS NULL
          AND (r.action_discovery_after_utc IS NULL OR r.action_discovery_after_utc<=#{now})
          AND JSON_CONTAINS_PATH(IF(JSON_VALID(t.rule_snapshot_json),t.rule_snapshot_json,'{}'),'one','$.actionOwnerBound')=1
        ORDER BY r.action_discovery_after_utc,r.id LIMIT 100
        """)
    List<Source> findDue(@Param("now") LocalDateTime now);
    @Select("SELECT rule_snapshot_json FROM iqc_inspection_task WHERE id=#{id}")
    String taskSnapshot(@Param("id") String id);
    @Select("SELECT action_discovered_at_utc IS NOT NULL FROM iqc_inspection_conversation_result WHERE id=#{id}")
    boolean isComplete(@Param("id") String id);
    @Update("""
        UPDATE iqc_inspection_conversation_result SET action_discovered_at_utc=UTC_TIMESTAMP(3),
          action_discovery_after_utc=NULL WHERE id=#{id} AND action_discovered_at_utc IS NULL
        """)
    int complete(@Param("id") String id);
    @Update("""
        UPDATE iqc_inspection_conversation_result SET action_discovery_after_utc=#{after}
        WHERE id=#{id} AND action_discovered_at_utc IS NULL
        """)
    int defer(@Param("id") String id,@Param("after") LocalDateTime after);
}
