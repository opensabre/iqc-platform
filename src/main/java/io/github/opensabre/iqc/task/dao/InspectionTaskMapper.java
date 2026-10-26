package io.github.opensabre.iqc.task.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InspectionTaskMapper extends BaseMapper<InspectionTask> {
    record BulkChildCount(String status,long countValue) { }
    @org.apache.ibatis.annotations.Select("SELECT status,COUNT(*) AS count_value FROM iqc_inspection_task WHERE bulk_parent_task_id=#{parentId} GROUP BY status")
    java.util.List<BulkChildCount> bulkChildCounts(@org.apache.ibatis.annotations.Param("parentId") String parentId);
    record OperationalCount(String mode,String measure,String status,long countValue) { }
    /** One read view; fixed status families, no transcript/owner/identity projection. */
    @org.apache.ibatis.annotations.Options(timeout=2)
    @org.apache.ibatis.annotations.Select("""
        SELECT CASE WHEN task_type='PIPELINE_EVALUATION' THEN 'EVALUATION' ELSE 'PRODUCTION' END AS mode,
          'TASK_STATE' AS measure,
          CASE WHEN status='PAUSED' AND model_budget_wait_code IS NOT NULL THEN 'BUDGET_WAIT' ELSE status END AS status,
          COUNT(*) AS count_value
        FROM iqc_inspection_task
        WHERE status IN ('QUEUED','RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED','PAUSED','WAITING_INPUT','MATERIALIZING','SCHEDULED')
        GROUP BY 1,3
        UNION ALL
        SELECT CASE WHEN t.task_type='PIPELINE_EVALUATION' THEN 'EVALUATION' ELSE 'PRODUCTION' END,
          'COMPLETED_5M',e.status,COUNT(*)
        FROM iqc_task_execution e JOIN iqc_inspection_task t ON t.id=e.task_id
        WHERE e.completed_at_utc >= UTC_TIMESTAMP(3)-INTERVAL 5 MINUTE AND e.completed_at_utc <= UTC_TIMESTAMP(3)
          AND e.status IN ('SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED')
        GROUP BY 1,3
        UNION ALL
        SELECT CASE WHEN t.task_type='PIPELINE_EVALUATION' THEN 'EVALUATION' ELSE 'PRODUCTION' END,
          'UNMEASURED_TERMINAL',e.status,COUNT(*)
        FROM iqc_task_execution e JOIN iqc_inspection_task t ON t.id=e.task_id
        WHERE e.completed_at_utc IS NULL AND e.status IN ('SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED')
        GROUP BY 1,3
        """)
    java.util.List<OperationalCount> operationalCounts();
}
