package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Internal workflow entry. Resolve organization access outside this independent short transaction. */
@Service
@RequiredArgsConstructor
public class ActionEnqueueService {
    public static final Set<String> REQUIRED_GRANTS=Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:action:enqueue");
    private final ActionProductionSourceValidator sources;
    private final ActionExecutionMapper executions;
    private final ActionDiscoveryMapper discovery;
    private final ActionAdapterRegistry adapters;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public List<String> enqueue(ActionProductionSourceValidator.Reference reference,ScheduledActorAccessService.Resolved actor) {
        requireActor(reference,actor,scope);
        var validated=sources.validate(reference);
        requireBound(validated.ownerBound(),actor);
        if(validated.policy()==null || validated.policy().actions().isEmpty()) {
            requireFresh(actor);
            discovery.complete(reference.resultId());
            return List.of();
        }
        try {
            String policyBody=json.writeValueAsString(validated.policy()),policyHash=SelectorAssetService.hash(policyBody);
            var saved=new ArrayList<String>();
            for(var action:validated.policy().actions()) {
                requireFresh(actor);
                if(ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)continue;
                if(action.type()==ActionExecutionContract.Type.ADD_TO_EVALUATION_CANDIDATE
                        &&action.effectiveAdapterVersion()==MinedEvaluationCandidateService.ADAPTER_VERSION
                        &&io.github.opensabre.iqc.quality.evaluation.MinedCandidateSelection.reasons(validated.context(),action.configuration()).isEmpty())continue;
                var source=new ActionExecutionContract.Source(reference.taskId(),reference.executionId(),reference.conversationId(),
                        reference.resultId(),validated.decisionReference(),policyHash,action.actionId(),reference.owner());
                String key=source.idempotencyKey(action.type());
                var existing=executions.selectById(key);
                if(existing!=null){requireSame(existing,source,action,validated,key,policyBody);saved.add(key);continue;}
                var row=new ActionExecution();row.setId(key);row.setIdempotencyKey(key);
                row.setTaskId(source.taskId());row.setExecutionId(source.executionId());row.setConversationId(source.conversationId());
                row.setSourceResultId(source.resultId());row.setDecisionReference(source.decisionReference());
                row.setActionPolicyHash(policyHash);row.setActionId(action.actionId());row.setActionType(action.type().name());
                row.setOwner(source.owner());row.setOwnerGroupId(validated.ownerGroupId());
                row.setInputSnapshotJson(validated.inputSnapshotJson());row.setInputSnapshotHash(validated.inputSnapshotHash());
                row.setPolicySnapshotJson(policyBody);row.setStatus("PENDING");row.setAttemptCount(0);row.setVersion(0L);
                row.setCreatedBy(source.owner());row.setUpdatedBy(source.owner());
                if(action.retry()!=null && action.retry().effectiveUntil()!=null && !Instant.now().isBefore(action.retry().effectiveUntil())) {
                    row.setStatus("EXPIRED");row.setLastErrorCode("ACTION_POLICY_EXPIRED");
                } else {
                    try {
                        var binding=adapters.require(action);
                        if(!actor.resourceCodes().containsAll(binding.adapter().requiredResources())) {
                            row.setStatus("BLOCKED_AUTHORIZATION");row.setLastErrorCode("ACTION_OPERATION_GRANT_MISSING");
                        }
                    }
                    catch(IqcException | IllegalArgumentException unavailable) {
                        row.setStatus("BLOCKED_ADAPTER");row.setLastErrorCode("ADAPTER_CONFIGURATION_UNAVAILABLE");
                    }
                }
                try {
                    if(executions.insert(row)!=1)throw IqcException.invalidState("动作记录保存失败");
                } catch(org.springframework.dao.DuplicateKeyException concurrent) {
                    var winner=executions.selectById(key);
                    if(winner==null)throw IqcException.invalidState("动作幂等标识冲突");
                    requireSame(winner,source,action,validated,key,policyBody);
                }
                saved.add(key);
            }
            requireFresh(actor);
            discovery.complete(reference.resultId());
            return List.copyOf(saved);
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("冻结动作策略无法序列化");
        }
    }
    /** Shared entry checks for enqueue and claim; resource requirements are server-owned. */
    public static void requireActor(ActionProductionSourceValidator.Reference reference,ScheduledActorAccessService.Resolved actor,IqcDataScope scope) {
        Instant now=Instant.now();
        if(reference==null || actor==null || actor.userId()==null || actor.resourceCodes()==null || actor.checkedAt()==null
                || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5))
                || !actor.resourceCodes().containsAll(REQUIRED_GRANTS) || !Objects.equals(reference.owner(),actor.owner())
                || !Objects.equals(scope.owner(),actor.owner()) || scope.canViewAll()!=actor.all()
                || !Objects.equals(scope.groupId(),actor.groupId()))
            throw IqcException.accessDenied("动作入队需要当前负责人授权");
    }
    public static void requireBound(ScheduledActorAccessService.CreationBound bound,ScheduledActorAccessService.Resolved actor) {
        if(bound==null || !Objects.equals(bound.userId(),actor.userId()) || !Objects.equals(bound.owner(),actor.owner())
                || actor.all() && !bound.all() || actor.groupId()!=null && !Objects.equals(actor.groupId(),bound.groupId()))
            throw IqcException.accessDenied("动作负责人稳定身份或冻结范围已变化");
    }
    public static void requireFresh(ScheduledActorAccessService.Resolved actor) {
        if(actor.checkedAt().isBefore(Instant.now().minusSeconds(30)))
            throw IqcException.accessDenied("动作入队授权证据已过期");
    }
    public static void requireSame(ActionExecution row,ActionExecutionContract.Source source,ActionPolicy.Action action,
                             ActionProductionSourceValidator.Validated validated,String key,String policyBody) {
        if(!Objects.equals(key,row.getId()) || !Objects.equals(key,row.getIdempotencyKey()) || !Objects.equals(source.taskId(),row.getTaskId())
                || !Objects.equals(source.executionId(),row.getExecutionId()) || !Objects.equals(source.conversationId(),row.getConversationId())
                || !Objects.equals(source.resultId(),row.getSourceResultId()) || !Objects.equals(source.decisionReference(),row.getDecisionReference())
                || !Objects.equals(source.actionPolicyHash(),row.getActionPolicyHash()) || !Objects.equals(action.actionId(),row.getActionId())
                || !Objects.equals(action.type().name(),row.getActionType()) || !Objects.equals(source.owner(),row.getOwner())
                || !Objects.equals(validated.ownerGroupId(),row.getOwnerGroupId()) || !Objects.equals(policyBody,row.getPolicySnapshotJson())
                || !Objects.equals(validated.inputSnapshotHash(),row.getInputSnapshotHash())
                || !Objects.equals(validated.inputSnapshotJson(),row.getInputSnapshotJson()))
            throw IqcException.invalidState("动作幂等请求内容不一致");
    }
}
