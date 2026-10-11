package io.github.opensabre.iqc.action;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;
@Mapper
public interface ActionExecutionMapper extends BaseMapper<ActionExecution> {
    /** Discovery is advisory: only a successful conditional claim grants execution. */
    @Select("""
        SELECT a.id,a.version FROM iqc_action_execution a
        WHERE a.status IN ('PENDING','WAITING_RETRY')
          AND ((a.status='PENDING' AND a.next_attempt_at_utc IS NULL) OR a.next_attempt_at_utc <= #{now})
          AND (a.action_type <> 'CREATE_REVIEW' OR EXISTS (
              SELECT 1 FROM iqc_inspection_task t WHERE t.id=a.task_id AND t.status <> 'RUNNING'))
        ORDER BY a.next_attempt_at_utc,a.id LIMIT 100
        """)
    List<ActionExecution> findDue(@Param("now") LocalDateTime now);

    @Select("""
        SELECT id,version,claim_token,claimed_at_utc FROM iqc_action_execution
        WHERE status='RUNNING' AND claimed_at_utc <= #{cutoff}
        ORDER BY claimed_at_utc,id LIMIT 100
        """)
    List<ActionExecution> findLost(@Param("cutoff") LocalDateTime cutoff);

    /** Prerequisite wait preserves attempt count and outcomes; version fences concurrent observations. */
    @Update("""
        UPDATE iqc_action_execution SET next_attempt_at_utc=#{after},version=version+1,
          updated_by='system',updated_time=#{now}
        WHERE id=#{id} AND version=#{version} AND version < 9223372036854775807
          AND status IN ('PENDING','WAITING_RETRY') AND #{after}>#{now}
          AND ((status='PENDING' AND next_attempt_at_utc IS NULL) OR next_attempt_at_utc<=#{now})
        """)
    int deferPrerequisite(@Param("id") String id,@Param("version") long version,
                          @Param("now") LocalDateTime now,@Param("after") LocalDateTime after);
    /** A claim is committed before external I/O; callers must validate the frozen source first. */
    @Update("""
        UPDATE iqc_action_execution
        SET status='RUNNING',attempt_count=attempt_count+1,version=version+1,
            claim_token=#{token},claimed_at_utc=#{now},next_attempt_at_utc=NULL,
            settled_at_utc=NULL,outcome=NULL,evidence_reference=NULL,result_reference=NULL,
            last_error_code=NULL,updated_by='system',updated_time=#{now}
        WHERE id=#{id} AND version=#{version}
          AND version < 9223372036854775807 AND attempt_count < 2147483647
          AND status IN ('PENDING','WAITING_RETRY')
          AND ((status='PENDING' AND next_attempt_at_utc IS NULL) OR next_attempt_at_utc <= #{now})
          AND CHAR_LENGTH(#{token})=32
        """)
    int claim(@Param("id") String id,@Param("version") long version,
              @Param("token") String token,@Param("now") LocalDateTime now);

    /** Local preflight failure before any attempt: never modifies a RUNNING claim. */
    @Update("""
        UPDATE iqc_action_execution
        SET status=#{status},last_error_code=#{errorCode},version=version+1,
            next_attempt_at_utc=NULL,updated_by='system',updated_time=#{now}
        WHERE id=#{id} AND version=#{version} AND version < 9223372036854775807
          AND status IN ('PENDING','WAITING_RETRY')
          AND #{status} IN ('EXPIRED','BLOCKED_ADAPTER','BLOCKED_SOURCE','BLOCKED_AUTHORIZATION')
        """)
    int blockReady(@Param("id") String id,@Param("version") long version,@Param("status") String status,
                   @Param("errorCode") String errorCode,@Param("now") LocalDateTime now);

    /** The service supplies the plan derived from frozen retry policy and adapter evidence. */
    @Update("""
        UPDATE iqc_action_execution
        SET status=#{next},outcome=#{outcome},version=version+1,
            next_attempt_at_utc=#{notBefore},settled_at_utc=#{now},
            evidence_reference=#{evidence},result_reference=#{result},
            last_error_code=#{errorCode},updated_by='system',updated_time=#{now}
        WHERE id=#{id} AND version=#{version} AND claim_token=#{token}
          AND version < 9223372036854775807 AND status='RUNNING'
          AND claimed_at_utc <= #{now}
          AND ((#{outcome}='CONFIRMED_SUCCESS' AND #{next}='COMPLETE')
            OR (#{outcome}='UNKNOWN' AND #{next}='REQUIRES_RECONCILIATION')
            OR (#{outcome}='CONFIRMED_FINAL_FAILURE' AND #{next}='FAILED')
            OR (#{outcome}='CONFIRMED_RETRYABLE_FAILURE' AND #{next} IN ('FAILED','WAITING_RETRY')))
          AND ((#{next}='WAITING_RETRY' AND #{notBefore} > #{now})
            OR (#{next}!='WAITING_RETRY' AND #{notBefore} IS NULL))
        """)
    int settle(@Param("id") String id,@Param("version") long version,@Param("token") String token,
               @Param("next") ActionExecutionContract.Next next,
               @Param("outcome") ActionExecutionContract.Outcome outcome,
               @Param("notBefore") LocalDateTime notBefore,@Param("now") LocalDateTime now,
               @Param("evidence") String evidence,@Param("result") String result,
               @Param("errorCode") String errorCode);

    /** Lost workers may have sent externally. They are never automatically returned to the queue. */
    @Update("""
        UPDATE iqc_action_execution
        SET status='REQUIRES_RECONCILIATION',outcome='UNKNOWN',version=version+1,
            next_attempt_at_utc=NULL,settled_at_utc=#{now},
            last_error_code='WORKER_CONFIRMATION_LOST',updated_by='system',updated_time=#{now}
        WHERE id=#{id} AND version=#{version} AND claim_token=#{token}
          AND version < 9223372036854775807 AND status='RUNNING'
          AND claimed_at_utc <= #{cutoff} AND #{cutoff} <= #{now}
        """)
    int quarantineLostClaim(@Param("id") String id,@Param("version") long version,
                            @Param("token") String token,@Param("cutoff") LocalDateTime cutoff,
                            @Param("now") LocalDateTime now);
    /** Only durable success can resolve UNKNOWN; neither attempts nor original claims are replaced. */
    @Update("""
        UPDATE iqc_action_execution SET status='COMPLETE',outcome='CONFIRMED_SUCCESS',version=version+1,
          next_attempt_at_utc=NULL,settled_at_utc=#{now},evidence_reference=#{evidence},
          result_reference=#{result},last_error_code=NULL,updated_by=#{actor},updated_time=#{now}
        WHERE id=#{id} AND version=#{version} AND claim_token=#{token}
          AND version < 9223372036854775807 AND status='REQUIRES_RECONCILIATION' AND outcome='UNKNOWN'
          AND attempt_count > 0 AND claimed_at_utc <= #{now}
          AND #{evidence} IS NOT NULL AND #{result} IS NOT NULL
        """)
    int reconcileSuccess(@Param("id") String id,@Param("version") long version,@Param("token") String token,
                         @Param("now") LocalDateTime now,@Param("evidence") String evidence,
                         @Param("result") String result,@Param("actor") String actor);

    /** Manual confirmation fences the complete immutable request at the exact write, not only a prior read. */
    @Update("""
        UPDATE iqc_action_execution SET status='COMPLETE',outcome='CONFIRMED_SUCCESS',version=version+1,
          next_attempt_at_utc=NULL,settled_at_utc=#{now},evidence_reference=#{evidence},
          result_reference=#{result},last_error_code=NULL,updated_by=#{actor},updated_time=#{now}
        WHERE id=#{request.executionId} AND version=#{version} AND claim_token=#{token}
          AND version < 9223372036854775807 AND BINARY status='REQUIRES_RECONCILIATION' AND BINARY outcome='UNKNOWN'
          AND attempt_count > 0 AND claimed_at_utc <= #{now}
          AND #{evidence} IS NOT NULL AND #{result} IS NOT NULL
          AND BINARY id=BINARY #{request.executionId}
          AND BINARY idempotency_key=BINARY #{request.idempotencyKey}
          AND BINARY task_id=BINARY #{request.source.taskId}
          AND BINARY execution_id=BINARY #{request.source.executionId}
          AND BINARY conversation_id=BINARY #{request.source.conversationId}
          AND BINARY source_result_id=BINARY #{request.source.resultId}
          AND BINARY decision_reference=BINARY #{request.source.decisionReference}
          AND BINARY action_policy_hash=BINARY #{request.source.actionPolicyHash}
          AND BINARY action_id=BINARY #{request.source.actionId}
          AND BINARY action_type=BINARY #{request.adapter.type}
          AND BINARY owner=BINARY #{request.source.owner}
          AND BINARY input_snapshot_json=BINARY #{request.inputSnapshotJson}
          AND BINARY input_snapshot_hash=BINARY #{request.inputSnapshotHash}
          AND BINARY policy_snapshot_json=BINARY #{request.policySnapshotJson}
          AND attempt_count=#{request.attempt}
        """)
    int reconcileSuccessBound(@Param("request") ActionAdapter.Request request,@Param("version") long version,
                              @Param("token") String token,@Param("now") LocalDateTime now,
                              @Param("evidence") String evidence,@Param("result") String result,@Param("actor") String actor);

    /** Local recovery fence only. The caller must first validate frozen source, actor, adapter and remaining policy. */
    @Update("""
        UPDATE iqc_action_execution
        SET status=CASE WHEN attempt_count=0 THEN 'PENDING' ELSE 'WAITING_RETRY' END,
            next_attempt_at_utc=CASE WHEN attempt_count=0 THEN NULL ELSE #{now} END,
            last_error_code=NULL,version=version+1,updated_by=#{actor},updated_time=#{now}
        WHERE BINARY id=BINARY #{id} AND version=#{version} AND version < 9223372036854775807
          AND BINARY owner=BINARY #{actor}
          AND BINARY status IN ('BLOCKED_SOURCE','BLOCKED_AUTHORIZATION','BLOCKED_ADAPTER')
          AND ((attempt_count=0 AND outcome IS NULL AND claim_token IS NULL AND claimed_at_utc IS NULL)
            OR (attempt_count>0 AND BINARY outcome='CONFIRMED_RETRYABLE_FAILURE'
              AND claim_token IS NOT NULL AND claimed_at_utc IS NOT NULL AND settled_at_utc IS NOT NULL))
        """)
    int recoverBlocked(@Param("id") String id,@Param("version") long version,
                       @Param("actor") String actor,@Param("now") LocalDateTime now);

}
