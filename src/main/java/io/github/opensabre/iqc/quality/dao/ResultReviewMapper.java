package io.github.opensabre.iqc.quality.dao;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.github.opensabre.iqc.quality.model.BusinessReviewQueueItem;

@Mapper public interface ResultReviewMapper extends BaseMapper<ResultReview> {
    /** Scope is applied before pagination/counting and follows the task owner, not the review requester. */
    @Select("""
        <script>
        SELECT r.id AS reviewId, c.id AS resultId, t.id AS taskId, t.name AS taskName,
               c.conversation_id AS conversationId, r.review_revision AS reviewRevision, r.status,
               r.request_comment AS requestComment, r.created_by AS createdBy, r.created_time AS createdTime,
               t.status AS taskStatus,
               CASE WHEN c.id = (SELECT n.id FROM iqc_inspection_conversation_result n
                   LEFT JOIN iqc_task_execution e ON e.id = n.execution_id AND e.task_id = n.task_id
                   WHERE n.task_id = t.id AND n.conversation_id = c.conversation_id
                   ORDER BY COALESCE(e.attempt_no, 0) DESC, n.created_time DESC, n.id DESC LIMIT 1) THEN TRUE ELSE FALSE END AS currentResult,
               CASE WHEN r.reviewed_result_json IS NULL THEN 'INITIAL'
                    WHEN JSON_VALID(r.reviewed_result_json) = 0 THEN 'INVALID'
                    WHEN JSON_TYPE(r.reviewed_result_json) = 'NULL' THEN 'INITIAL'
                    WHEN r.status = 'COMPLETED' AND (JSON_EXTRACT(r.reviewed_result_json, '$.workflow') IS NULL
                         OR JSON_TYPE(JSON_EXTRACT(r.reviewed_result_json, '$.workflow')) = 'NULL') THEN 'INITIAL'
                    WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.reviewed_result_json, '$.workflow.schemaVersion')),
                                  JSON_UNQUOTE(JSON_EXTRACT(r.reviewed_result_json, '$.schemaVersion'))) = 'iqc-review-workflow-v1'
                         AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.reviewed_result_json, '$.workflow.stage')),
                                      JSON_UNQUOTE(JSON_EXTRACT(r.reviewed_result_json, '$.stage'))) IN ('SECOND_REVIEW', 'ARBITRATION')
                    THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.reviewed_result_json, '$.workflow.stage')),
                                  JSON_UNQUOTE(JSON_EXTRACT(r.reviewed_result_json, '$.stage')))
                    ELSE 'INVALID' END AS stage
        FROM iqc_result_review r
        JOIN iqc_inspection_conversation_result c ON c.id = r.business_result_id
        JOIN iqc_inspection_task t ON t.id = c.task_id
        WHERE r.target_type = 'BUSINESS'
        <if test="status != null">AND r.status = #{status}</if>
        <if test="taskId != null">AND t.id = #{taskId}</if>
        <if test="!all">AND (t.created_by = #{owner} OR t.owner_group_id = #{groupId})</if>
        ORDER BY r.created_time DESC, r.id DESC
        </script>
        """)
    IPage<BusinessReviewQueueItem> selectBusinessQueue(IPage<BusinessReviewQueueItem> page,
            @Param("status") String status, @Param("taskId") String taskId,
            @Param("all") boolean all, @Param("owner") String owner, @Param("groupId") String groupId);

    /** Label rounds use their own result identity but the same task-based SQL scope before pagination. */
    @Select("""
        <script>
        SELECT r.id AS reviewId, l.id AS labelResultId, t.id AS taskId, t.name AS taskName,
               c.conversation_id AS conversationId, l.label_id AS labelId,
               l.label_version_no AS labelVersionNo, l.value_code AS valueCode,
               r.review_revision AS reviewRevision, r.status, r.request_comment AS requestComment,
               r.created_by AS createdBy, r.created_time AS createdTime,
               CASE WHEN c.id = (SELECT n.id FROM iqc_inspection_conversation_result n
                   LEFT JOIN iqc_task_execution e ON e.id = n.execution_id AND e.task_id = n.task_id
                   WHERE n.task_id = t.id AND n.conversation_id = c.conversation_id
                   ORDER BY COALESCE(e.attempt_no, 0) DESC, n.created_time DESC, n.id DESC LIMIT 1) THEN TRUE ELSE FALSE END AS currentResult
        FROM iqc_result_review r
        JOIN iqc_inspection_label_result l ON l.id = r.label_result_id
        JOIN iqc_inspection_conversation_result c ON c.id = l.conversation_result_id
        JOIN iqc_inspection_task t ON t.id = c.task_id
        WHERE r.target_type = 'LABEL'
        <if test="status != null">AND r.status = #{status}</if>
        <if test="taskId != null">AND t.id = #{taskId}</if>
        <if test="!all">AND (t.created_by = #{owner} OR t.owner_group_id = #{groupId})</if>
        ORDER BY r.created_time DESC, r.id DESC
        </script>
        """)
    IPage<io.github.opensabre.iqc.label.model.LabelReviewQueueItem> selectLabelQueue(
            IPage<io.github.opensabre.iqc.label.model.LabelReviewQueueItem> page,
            @Param("status") String status, @Param("taskId") String taskId,
            @Param("all") boolean all, @Param("owner") String owner, @Param("groupId") String groupId);
}
