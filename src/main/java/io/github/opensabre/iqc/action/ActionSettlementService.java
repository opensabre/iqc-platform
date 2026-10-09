package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
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

/** Receipt recording never requires a fresh send grant: revocation must not erase an already sent outcome. */
@Service
@RequiredArgsConstructor
public class ActionSettlementService {
    private final ActionExecutionMapper executions;
    private final ActionAdapterRegistry adapters;
    private final ObjectMapper json;

    /** Returns false for an obsolete claim; cannot overwrite reconciliation or a newer attempt. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public boolean settle(ActionAdapter.Request request,long claimVersion,String claimToken,ActionAdapter.Receipt receipt) {
        if(request==null || receipt==null || claimVersion<1 || claimToken==null || !claimToken.matches("[a-f0-9]{32}"))
            throw IqcException.invalidArgument("动作结算缺少领取或回执证据");
        receipt.requireRequest(request);
        var row=executions.selectOne(Wrappers.<ActionExecution>lambdaQuery()
                .eq(ActionExecution::getId,request.executionId()).last("FOR UPDATE"));
        if(row==null || !"RUNNING".equals(row.getStatus()) || !Objects.equals(row.getVersion(),claimVersion)
                || !Objects.equals(row.getClaimToken(),claimToken))return false;
        var source=request.source();
        if(!Objects.equals(row.getIdempotencyKey(),request.idempotencyKey()) || !Objects.equals(row.getAttemptCount(),request.attempt())
                || !Objects.equals(row.getTaskId(),source.taskId()) || !Objects.equals(row.getExecutionId(),source.executionId())
                || !Objects.equals(row.getConversationId(),source.conversationId()) || !Objects.equals(row.getSourceResultId(),source.resultId())
                || !Objects.equals(row.getDecisionReference(),source.decisionReference()) || !Objects.equals(row.getOwner(),source.owner())
                || !Objects.equals(row.getActionId(),source.actionId()) || !Objects.equals(row.getActionPolicyHash(),source.actionPolicyHash())
                || !Objects.equals(row.getActionType(),request.adapter().type().name())
                || !Objects.equals(row.getInputSnapshotJson(),request.inputSnapshotJson())
                || !Objects.equals(row.getInputSnapshotHash(),request.inputSnapshotHash())
                || !Objects.equals(row.getPolicySnapshotJson(),request.policySnapshotJson()))
            throw IqcException.invalidState("动作结算与冻结请求不一致");
        try {
            var policy=json.readValue(row.getPolicySnapshotJson(),ActionPolicy.class);
            var action=policy.actions().stream().filter(value->row.getActionId().equals(value.actionId())).findFirst().orElse(null);
            if(action==null || !action.type().name().equals(row.getActionType()))
                throw IqcException.invalidState("动作结算缺少冻结策略");
            var binding=adapters.require(action);
            if(!binding.definition().equals(request.adapter()))throw IqcException.invalidState("动作结算适配协议不一致");
            Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var plan=ActionExecutionContract.plan(receipt.outcome(),request.attempt(),binding.definition().retrySafe(),action.retry(),now);
            LocalDateTime due=plan.notBefore()==null?null:LocalDateTime.ofInstant(plan.notBefore(),ZoneOffset.UTC);
            if(due!=null && (due.getYear()<1000 || due.getYear()>9999))throw IqcException.invalidState("动作重试时间超出存储范围");
            return executions.settle(row.getId(),claimVersion,claimToken,plan.next(),receipt.outcome(),due,
                    LocalDateTime.ofInstant(now,ZoneOffset.UTC),receipt.evidenceReference(),receipt.resultReference(),receipt.errorCode())==1;
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("动作结算冻结策略无效");
        }
    }
}
