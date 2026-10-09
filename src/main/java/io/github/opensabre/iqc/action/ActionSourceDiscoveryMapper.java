package io.github.opensabre.iqc.action;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Only source references are paged; current source/authorization is checked again during enqueue. */
@Mapper
public interface ActionSourceDiscoveryMapper {
    record Source(String id,String taskId,String executionId,String conversationId,String owner) { }
    @Select("""
        SELECT r.id,r.task_id,r.execution_id,r.conversation_id,t.created_by AS owner
        FROM iqc_inspection_conversation_result r
        JOIN iqc_inspection_task t ON t.id=r.task_id AND t.current_execution_id=r.execution_id
        JOIN iqc_task_execution e ON e.id=r.execution_id AND e.task_id=t.id
        WHERE r.id > #{after} AND r.aggregation_mode='PIPELINE' AND t.task_type='PIPELINE'
          AND t.status IN ('RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED')
          AND e.status IN ('RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED')
          AND COALESCE(t.pause_requested,0)=0 AND COALESCE(t.cancel_requested,0)=0
        ORDER BY r.id LIMIT 100
        """)
    List<Source> findAfter(@Param("after") String after);
}
