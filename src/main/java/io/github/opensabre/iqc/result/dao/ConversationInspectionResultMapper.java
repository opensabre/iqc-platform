package io.github.opensabre.iqc.result.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConversationInspectionResultMapper extends BaseMapper<ConversationInspectionResult> {
    /** Rank before filtering so an old matching failure/score cannot replace the current business result. */
    @Select("""
            <script>
            SELECT ranked.* FROM (
              SELECT r.*, ROW_NUMBER() OVER (
                PARTITION BY r.task_id, r.conversation_id
                ORDER BY COALESCE(e.attempt_no, 0) DESC, r.created_time DESC, r.id DESC
              ) AS iqc_rank
              FROM iqc_inspection_conversation_result r
              LEFT JOIN iqc_task_execution e ON e.id = r.execution_id AND e.task_id = r.task_id
              WHERE r.task_id IN
              <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">#{taskId}</foreach>
            ) ranked WHERE ranked.iqc_rank = 1 AND ranked.score_status IS NOT NULL
            <if test="scoreStatus != null">AND ranked.score_status = #{scoreStatus}</if>
            <if test="riskLevel != null">AND ranked.risk_level = #{riskLevel}</if>
            <if test="minScore != null">AND ranked.score_status = 'FINAL' AND ranked.final_score &gt;= #{minScore}</if>
            <if test="maxScore != null">AND ranked.score_status = 'FINAL' AND ranked.final_score &lt;= #{maxScore}</if>
            ORDER BY ranked.created_time DESC, ranked.task_id, ranked.conversation_id, ranked.id
            </script>
            """)
    IPage<ConversationInspectionResult> selectBusinessPage(IPage<ConversationInspectionResult> page,
            @Param("taskIds") List<String> taskIds, @Param("scoreStatus") String scoreStatus,
            @Param("riskLevel") String riskLevel, @Param("minScore") java.math.BigDecimal minScore,
            @Param("maxScore") java.math.BigDecimal maxScore);

    /** Execution attempt is authoritative when retries and database/app timestamps disagree. */
    @Select("""
            SELECT r.* FROM iqc_inspection_conversation_result r
            LEFT JOIN iqc_task_execution e ON e.id = r.execution_id AND e.task_id = r.task_id
            WHERE r.task_id = #{taskId} AND r.conversation_id = #{conversationId}
            ORDER BY COALESCE(e.attempt_no, 0) DESC, r.created_time DESC, r.id DESC
            LIMIT 1
            """)
    ConversationInspectionResult selectLatestForTaskConversation(@Param("taskId") String taskId,
                                                                   @Param("conversationId") String conversationId);

    /** Explicit report run selection never falls back to another attempt. */
    @Select("""
            SELECT r.* FROM iqc_inspection_conversation_result r
            WHERE r.task_id = #{taskId} AND r.execution_id = #{executionId} AND r.conversation_id = #{conversationId}
            ORDER BY r.created_time DESC, r.id DESC
            LIMIT 1
            """)
    ConversationInspectionResult selectForTaskExecutionConversation(@Param("taskId") String taskId,
                                                                      @Param("executionId") String executionId,
                                                                      @Param("conversationId") String conversationId);

    /** One first/latest recorded run per selected conversation, bounded regardless of how many retries the task has. */
    @Select("""
            <script>
            SELECT ranked.* FROM (
              SELECT r.*, ROW_NUMBER() OVER (
                PARTITION BY r.conversation_id
                ORDER BY COALESCE(e.attempt_no, 0)
                <choose><when test="latest">DESC</when><otherwise>ASC</otherwise></choose>,
                r.created_time <choose><when test="latest">DESC</when><otherwise>ASC</otherwise></choose>,
                r.id <choose><when test="latest">DESC</when><otherwise>ASC</otherwise></choose>
              ) AS iqc_rank
              FROM iqc_inspection_conversation_result r
              LEFT JOIN iqc_task_execution e ON e.id = r.execution_id AND e.task_id = r.task_id
              WHERE r.task_id = #{taskId} AND r.conversation_id IN
              <foreach collection="conversationIds" item="conversationId" open="(" separator="," close=")">#{conversationId}</foreach>
            ) ranked WHERE ranked.iqc_rank = 1
            </script>
            """)
    List<ConversationInspectionResult> selectPolicyRunsForTaskConversations(@Param("taskId") String taskId,
            @Param("conversationIds") List<String> conversationIds, @Param("latest") boolean latest);

    /** One canonical row per conversation, ranked by execution attempt before timestamp. */
    @Select("""
            SELECT ranked.* FROM (
              SELECT r.*, ROW_NUMBER() OVER (
                PARTITION BY r.conversation_id
                ORDER BY COALESCE(e.attempt_no, 0) DESC, r.created_time DESC, r.id DESC
              ) AS iqc_rank
              FROM iqc_inspection_conversation_result r
              LEFT JOIN iqc_task_execution e ON e.id = r.execution_id AND e.task_id = r.task_id
              WHERE r.task_id = #{taskId}
            ) ranked WHERE ranked.iqc_rank = 1
            """)
    List<ConversationInspectionResult> selectLatestForTask(@Param("taskId") String taskId);

    /** Dashboard selects the latest attempt inside its requested result-time window. */
    @Select("""
            <script>
            SELECT ranked.* FROM (
              SELECT r.*, ROW_NUMBER() OVER (
                PARTITION BY r.task_id, r.conversation_id
                ORDER BY COALESCE(e.attempt_no, 0) DESC, r.created_time DESC, r.id DESC
              ) AS iqc_rank
              FROM iqc_inspection_conversation_result r
              LEFT JOIN iqc_task_execution e ON e.id = r.execution_id AND e.task_id = r.task_id
              WHERE r.task_id IN
              <foreach collection="taskIds" item="taskId" open="(" separator="," close=")">#{taskId}</foreach>
              <if test="from != null">AND r.created_time &gt;= #{from}</if>
              <if test="to != null">AND r.created_time &lt; #{to}</if>
            ) ranked WHERE ranked.iqc_rank = 1
            </script>
            """)
    List<ConversationInspectionResult> selectLatestInWindow(@Param("taskIds") List<String> taskIds,
                                                             @Param("from") java.util.Date from,
                                                             @Param("to") java.util.Date to);
}
