package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Manual proof lookup outside ledger locks; UNKNOWN never authorizes another execution. */
@Service @RequiredArgsConstructor
public class ActionReconciliationService {
    public record Response(String id,String state) { }
    private record Prepared(ActionAdapter.Request request,ActionAdapter adapter,String token) { }
    private final ActionExecutionMapper actions;
    private final ActionAdapterRegistry adapters;
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private final ActionReconciliationStateService state;

    /** The caller supplies only an observation and audit reason, never evidence or outcome. */
    @Transactional(propagation=Propagation.NEVER)
    public Response reconcile(String resultId,String id,long version,String reason) {
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}")
                || id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}")
                || version<1 || reason==null || reason.isBlank() || reason.trim().length()>1000)
            throw IqcException.invalidArgument("动作核对标识、版本或原因无效");
        // Stable identity is resolved before any owning-service database transaction.
        var actor=actors.capture();
        var prepared=prepare(resultId,id,version,actor);
        if(prepared==null)return new Response(id,"STALE_OBSERVATION");
        var receipt=prepared.adapter().reconcile(prepared.request());
        if(receipt==null)return new Response(id,"UNCONFIRMED");
        // Even an inconclusive receipt must belong to the original frozen request.
        receipt.requireRequest(prepared.request());
        if(receipt.outcome()==ActionExecutionContract.Outcome.UNKNOWN)
            return new Response(id,"UNCONFIRMED");
        if(receipt.outcome()!=ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)
            throw IqcException.invalidState("动作核对仅接受真实成功产物");
        // A changed source/owner or ledger observation cannot be overwritten by a slow lookup.
        var current=prepare(resultId,id,version,actor);
        if(current==null || !current.request().equals(prepared.request()) || !current.token().equals(prepared.token()))
            return new Response(id,"STALE_OBSERVATION");
        boolean recorded=state.confirm(prepared.request(),version,prepared.token(),receipt,actor.owner(),reason);
        return new Response(id,recorded ? "CONFIRMED" : "STALE_OBSERVATION");
    }

    private Prepared prepare(String resultId,String id,long version,ScheduledActorAccessService.CreationBound actor) {
        var row=actions.selectById(id);
        if(row==null || !Objects.equals(resultId,row.getSourceResultId()))throw IqcException.notFound("结果动作不存在");
        var result=results.selectById(resultId);
        var task=tasks.selectById(row.getTaskId());
        if(result==null || task==null || !Objects.equals(result.getTaskId(),row.getTaskId())
                || !Objects.equals(result.getExecutionId(),row.getExecutionId())
                || !Objects.equals(result.getConversationId(),row.getConversationId())
                || !Objects.equals(row.getOwner(),task.getCreatedBy()) || !Objects.equals(actor.owner(),row.getOwner())
                || !Objects.equals(scope.owner(),row.getOwner()) || !scope.canView(task.getCreatedBy(),task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权核对动作来源");
        if(!Objects.equals(row.getVersion(),version) || !"REQUIRES_RECONCILIATION".equals(row.getStatus())
                || !"UNKNOWN".equals(row.getOutcome()))return null;
        if(row.getClaimToken()==null || !row.getClaimToken().matches("[a-f0-9]{32}")
                || row.getAttemptCount()==null || row.getAttemptCount()<1 || row.getClaimedAtUtc()==null)
            throw IqcException.invalidState("动作缺少原始领取证据");
        try {
            var policy=json.readValue(row.getPolicySnapshotJson(),ActionPolicy.class);
            var action=policy.actions().stream().filter(value->row.getActionId().equals(value.actionId())).findFirst().orElseThrow();
            if(!action.type().name().equals(row.getActionType()))throw IqcException.invalidState("动作类型与冻结政策不一致");
            var binding=adapters.require(action);
            var source=new ActionExecutionContract.Source(row.getTaskId(),row.getExecutionId(),row.getConversationId(),
                    row.getSourceResultId(),row.getDecisionReference(),row.getActionPolicyHash(),row.getActionId(),row.getOwner());
            var request=new ActionAdapter.Request(row.getId(),row.getIdempotencyKey(),source,binding.definition(),
                    row.getAttemptCount(),row.getInputSnapshotJson(),row.getInputSnapshotHash(),row.getPolicySnapshotJson());
            var input=json.readTree(request.inputSnapshotJson());
            if(!"iqc-action-production-input-v1".equals(input.path("schemaVersion").asText())
                    || !row.getTaskId().equals(input.path("taskId").asText())
                    || !row.getExecutionId().equals(input.path("executionId").asText())
                    || !row.getConversationId().equals(input.path("conversationId").asText())
                    || !row.getSourceResultId().equals(input.path("resultId").asText())
                    || !row.getOwner().equals(input.path("owner").asText()))throw IqcException.invalidState("动作冻结来源不一致");
            var taskSnapshot=json.readTree(input.path("taskSnapshotJson").asText());
            var bound=json.treeToValue(taskSnapshot.path("actionOwnerBound"),ScheduledActorAccessService.CreationBound.class);
            if(bound==null || !"iqc-schedule-owner-scope-v1".equals(bound.schemaVersion())
                    || !Objects.equals(actor.userId(),bound.userId()) || !Objects.equals(actor.owner(),bound.owner()))
                throw IqcException.accessDenied("动作负责人稳定身份已变化");
            return new Prepared(request,binding.adapter(),row.getClaimToken());
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw IqcException.invalidState("动作冻结核对证据损坏");
        }
    }
}
