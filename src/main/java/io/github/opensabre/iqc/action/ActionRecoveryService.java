package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/** Revalidate a blocked original action without sending; recovery and its reason share one local transaction. */
@Service @RequiredArgsConstructor
public class ActionRecoveryService {
    public static final String RESOURCE="iqc:action:recover";
    private final ActionExecutionMapper actions;
    private final ActionProductionSourceValidator sources;
    private final ActionAdapterRegistry adapters;
    private final ActionStateEventMapper events;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    /** The entry point resolves current owner authorization outside this transaction before calling. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public boolean recover(String resultId,String id,long version,ScheduledActorAccessService.Resolved actor,String reason) {
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}")
                || id==null || !id.matches("[a-f0-9]{64}") || version<0 || version==Long.MAX_VALUE
                || reason==null || reason.isBlank() || reason.trim().length()>1000)
            throw IqcException.invalidArgument("动作恢复标识、版本或原因无效");
        if(actor==null || actor.resourceCodes()==null || !actor.resourceCodes().contains(RESOURCE))
            throw IqcException.accessDenied("缺少动作恢复权限");
        var observed=actions.selectById(id);
        if(observed==null || !Objects.equals(resultId,observed.getSourceResultId()))throw IqcException.notFound("结果动作不存在");
        var reference=new ActionProductionSourceValidator.Reference(observed.getTaskId(),observed.getExecutionId(),
                observed.getConversationId(),resultId,observed.getOwner());
        ActionEnqueueService.requireActor(reference,actor,scope);
        if(!Objects.equals(observed.getVersion(),version) || !eligible(observed))return false;
        // Preserve the production Task -> Execution -> Result -> Action lock order.
        var validated=sources.validate(reference);ActionEnqueueService.requireBound(validated.ownerBound(),actor);
        var row=actions.selectOne(Wrappers.<ActionExecution>lambdaQuery().eq(ActionExecution::getId,id).last("FOR UPDATE"));
        if(row==null || !Objects.equals(row.getVersion(),version) || !eligible(row))return false;
        try {
            var prepared=ActionFrozenPolicy.validate(row,reference,validated,json);
            var now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if(prepared.exhausted(row,now))return false;
            var binding=adapters.require(prepared.action());
            if(!actor.resourceCodes().containsAll(binding.adapter().requiredResources()))throw IqcException.accessDenied("缺少原动作业务权限");
            if(row.getAttemptCount()>0) {
                var plan=ActionExecutionContract.plan(ActionExecutionContract.Outcome.CONFIRMED_RETRYABLE_FAILURE,
                        row.getAttemptCount(),binding.definition().retrySafe(),prepared.action().retry(),row.getSettledAtUtc().toInstant(ZoneOffset.UTC));
                if(plan.next()!=ActionExecutionContract.Next.WAITING_RETRY || plan.notBefore().isAfter(now))return false;
            }
            if(prepared.action().type()==ActionExecutionContract.Type.CREATE_REVIEW && "RUNNING".equals(validated.taskStatus()))return false;
            if(!binding.adapter().ready(prepared.action(),validated))return false;
            ActionEnqueueService.requireFresh(actor);
            if(actions.recoverBlocked(id,version,actor.owner(),LocalDateTime.ofInstant(now,ZoneOffset.UTC))!=1)return false;
            if(events.recordRecoveryReason(id,version+1,version,resultId,actor.owner(),reason.trim())!=1)
                throw IqcException.invalidState("动作恢复原因未能与状态同时保存");
            return true;
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("动作恢复冻结政策损坏");
        }
    }
    private static boolean eligible(ActionExecution row) {
        if(!Set.of("BLOCKED_SOURCE","BLOCKED_AUTHORIZATION","BLOCKED_ADAPTER").contains(row.getStatus()==null?"":row.getStatus())
                || row.getAttemptCount()==null || row.getAttemptCount()<0)return false;
        if(row.getAttemptCount()==0)return row.getOutcome()==null && row.getClaimToken()==null && row.getClaimedAtUtc()==null;
        return "CONFIRMED_RETRYABLE_FAILURE".equals(row.getOutcome()) && row.getClaimToken()!=null
                && row.getClaimToken().matches("[a-f0-9]{32}") && row.getClaimedAtUtc()!=null && row.getSettledAtUtc()!=null
                && !row.getSettledAtUtc().isBefore(row.getClaimedAtUtc());
    }
}
