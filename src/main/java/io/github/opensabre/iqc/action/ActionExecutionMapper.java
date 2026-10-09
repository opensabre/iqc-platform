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
        SELECT id,version FROM iqc_action_execution
        WHERE status IN ('PENDING','WAITING_RETRY')
          AND ((status='PENDING' AND next_attempt_at_utc IS NULL) OR next_attempt_at_utc <= #{now})
        ORDER BY next_attempt_at_utc,id LIMIT 100
        """)
    List<ActionExecution> findDue(@Param("now") LocalDateTime now);

    @Select("""
        SELECT id,version,claim_token,claimed_at_utc FROM iqc_action_execution
        WHERE status='RUNNING' AND claimed_at_utc <= #{cutoff}
        ORDER BY claimed_at_utc,id LIMIT 100
        """)
    List<ActionExecution> findLost(@Param("cutoff") LocalDateTime cutoff);

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
}
