package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Objects;
import java.util.Set;

/** Local owning transaction; no send, receipt alteration or machine-result write. */
@Service @RequiredArgsConstructor
public class ActionFailureEscalationService {
    public record Response(String id,String state,String reviewId) { }
    private final ActionExecutionMapper actions;
    private final ActionProductionSourceValidator sources;
    private final BusinessItemReviewService reviews;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Response escalate(String id,long version) {
        if(id==null || !id.matches("[a-f0-9]{64}") || version<1)
            throw IqcException.invalidArgument("失败升级动作观察无效");
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        var initial=actions.selectById(id);
        if(initial==null)throw IqcException.notFound("失败升级动作不存在");
        if(!Objects.equals(scope.owner(),initial.getOwner()))throw IqcException.accessDenied("失败升级负责人不一致");
        // The existing production validator locks Task -> Execution -> Result before Action.
        var validated=sources.validate(new ActionProductionSourceValidator.Reference(initial.getTaskId(),
                initial.getExecutionId(),initial.getConversationId(),initial.getSourceResultId(),initial.getOwner()));
        scope.requireScheduledBound(validated.ownerBound());
        var row=actions.selectOne(Wrappers.<ActionExecution>lambdaQuery().eq(ActionExecution::getId,id).last("FOR UPDATE"));
        if(row==null || !Objects.equals(row.getVersion(),version) || !"FAILED".equals(row.getStatus()))
            return new Response(id,"STALE_OBSERVATION",null);
        String token=verifiedToken(row,validated);
        if(token==null)return new Response(id,"NOT_CONFIGURED",null);
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        var review=reviews.requestEscalation(row.getSourceResultId(),token,reason(id));
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        return new Response(id,review==null?"WAITING_REVIEW":"ESCALATED",review==null?null:review.getId());
    }

    /** Historical proof only: no current-EID requirement, send grants, locks, creation or receipt changes. */
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Response lookup(String resultId,String id,io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound actor) {
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}")
                || id==null || !id.matches("[a-f0-9]{64}"))throw IqcException.invalidArgument("失败升级动作标识无效");
        var row=actions.selectById(id);
        if(row==null || !Objects.equals(resultId,row.getSourceResultId()))throw IqcException.notFound("结果失败升级动作不存在");
        if(!Objects.equals(scope.owner(),row.getOwner()))throw IqcException.accessDenied("无权核对失败升级来源");
        var validated=sources.validateHistorical(new ActionProductionSourceValidator.Reference(row.getTaskId(),
                row.getExecutionId(),row.getConversationId(),row.getSourceResultId(),row.getOwner()));
        if(actor==null || validated.ownerBound()==null || !Objects.equals(actor.owner(),row.getOwner())
                || !Objects.equals(actor.userId(),validated.ownerBound().userId())
                || !scope.canView(row.getOwner(),validated.ownerGroupId()))
            throw IqcException.accessDenied("无权核对失败升级稳定负责人来源");
        if(!"FAILED".equals(row.getStatus()))return new Response(id,"NOT_ELIGIBLE",null);
        String token=verifiedToken(row,validated);
        if(token==null)return new Response(id,"NOT_CONFIGURED",null);
        var review=reviews.lookupEscalation(row.getSourceResultId(),token,reason(id));
        return new Response(id,review==null?"UNCONFIRMED":"ESCALATED",review==null?null:review.getId());
    }

    private static String reason(String id) {return "动作失败升级复核，原动作记录："+id;}
    private String verifiedToken(ActionExecution row,ActionProductionSourceValidator.Validated validated) {
        if(!Set.of("CONFIRMED_FINAL_FAILURE","CONFIRMED_RETRYABLE_FAILURE").contains(Objects.toString(row.getOutcome(),""))
                || row.getVersion()==null || row.getVersion()<1 || row.getAttemptCount()==null || row.getAttemptCount()<1
                || row.getClaimToken()==null || !row.getClaimToken().matches("[a-f0-9]{32}")
                || row.getClaimedAtUtc()==null || row.getSettledAtUtc()==null)
            throw IqcException.invalidState("失败升级缺少已确认的领取结算");
        try {
            var policy=validated.policy();
            if(policy==null)throw IqcException.invalidState("失败升级缺少冻结动作策略");
            String body=json.writeValueAsString(policy);
            var action=policy.actions().stream().filter(value->value.actionId().equals(row.getActionId())).findFirst()
                    .orElseThrow(()->IqcException.invalidState("失败升级缺少原动作"));
            var source=new ActionExecutionContract.Source(validated.reference().taskId(),validated.reference().executionId(),
                    validated.reference().conversationId(),validated.reference().resultId(),validated.decisionReference(),
                    SelectorAssetService.hash(body),action.actionId(),validated.reference().owner());
            ActionEnqueueService.requireSame(row,source,action,validated,source.idempotencyKey(action.type()),body);
            if(action.failureHandling()!=ActionPolicy.FailureHandling.ESCALATE_REVIEW)return null;
            if(ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)
                throw IqcException.invalidState("失败升级原触发条件不一致");
            return SelectorAssetService.hash("iqc-action-failure-review-v1:"+source.idempotencyKey(action.type()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw IqcException.invalidState("失败升级冻结策略损坏");
        }
    }
}
