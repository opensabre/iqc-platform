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
import java.util.UUID;

/** Claim commits before the returned immutable request can be sent. Never invokes an adapter here. */
@Service
@RequiredArgsConstructor
public class ActionClaimService {
    public record Claim(ActionAdapter.Request request,long version,String token) { }
    private final ActionExecutionMapper executions;
    private final ActionProductionSourceValidator sources;
    private final ActionAdapterRegistry adapters;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Claim claim(String id,long expectedVersion,ScheduledActorAccessService.Resolved actor) {
        if(id==null || !id.matches("[a-f0-9]{64}") || expectedVersion<0 || expectedVersion>Long.MAX_VALUE-2)
            throw IqcException.invalidArgument("动作领取标识或版本无效");
        var observed=executions.selectById(id);
        if(observed==null || !Objects.equals(observed.getVersion(),expectedVersion))return null;
        var reference=new ActionProductionSourceValidator.Reference(observed.getTaskId(),observed.getExecutionId(),
                observed.getConversationId(),observed.getSourceResultId(),observed.getOwner());
        ActionEnqueueService.requireActor(reference,actor,scope);
        // Preserve Task -> Execution -> Result -> Action order. Settlement locks only Action.
        var validated=sources.validate(reference);
        ActionEnqueueService.requireBound(validated.ownerBound(),actor);
        var row=executions.selectOne(Wrappers.<ActionExecution>lambdaQuery().eq(ActionExecution::getId,id).last("FOR UPDATE"));
        if(row==null || !Objects.equals(row.getVersion(),expectedVersion)
                || !("PENDING".equals(row.getStatus()) || "WAITING_RETRY".equals(row.getStatus())))return null;
        Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime utc=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
        if(row.getNextAttemptAtUtc()!=null && row.getNextAttemptAtUtc().isAfter(utc)
                || "WAITING_RETRY".equals(row.getStatus()) && row.getNextAttemptAtUtc()==null)return null;
        try {
            var prepared=ActionFrozenPolicy.validate(row,reference,validated,json);
            var action=prepared.action();var source=prepared.source();String policyBody=prepared.policyBody();
            if(prepared.exhausted(row,now)) {
                ActionEnqueueService.requireFresh(actor);
                executions.blockReady(id,expectedVersion,"EXPIRED","ACTION_ATTEMPTS_OR_DEADLINE_EXHAUSTED",utc);
                return null;
            }
            ActionAdapterRegistry.Binding binding;
            try {binding=adapters.require(action);}
            catch(IqcException | IllegalArgumentException unavailable) {
                ActionEnqueueService.requireFresh(actor);
                executions.blockReady(id,expectedVersion,"BLOCKED_ADAPTER","ADAPTER_CONFIGURATION_UNAVAILABLE",utc);
                return null;
            }
            if(!actor.resourceCodes().containsAll(binding.adapter().requiredResources())) {
                ActionEnqueueService.requireFresh(actor);
                executions.blockReady(id,expectedVersion,"BLOCKED_AUTHORIZATION","ACTION_OPERATION_GRANT_MISSING",utc);
                return null;
            }
            if(action.type()==ActionExecutionContract.Type.CREATE_REVIEW && "RUNNING".equals(validated.taskStatus()))return null;
            if(!binding.adapter().ready(action,validated)) {
                ActionEnqueueService.requireFresh(actor);
                executions.deferPrerequisite(id,expectedVersion,utc,utc.plusMinutes(1));
                return null;
            }
            String token=UUID.randomUUID().toString().replace("-","");
            var request=new ActionAdapter.Request(id,row.getIdempotencyKey(),source,binding.definition(),row.getAttemptCount()+1,
                    row.getInputSnapshotJson(),row.getInputSnapshotHash(),policyBody);
            ActionEnqueueService.requireFresh(actor);
            if(executions.claim(id,expectedVersion,token,utc)!=1)return null;
            return new Claim(request,expectedVersion+1,token);
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("动作领取策略无法序列化");
        }
    }
}
