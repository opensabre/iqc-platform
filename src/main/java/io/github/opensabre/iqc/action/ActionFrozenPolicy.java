package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;
import java.util.Objects;

/** Shared immutable-policy validation for claim and blocked recovery; never performs state writes or adapter I/O. */
final class ActionFrozenPolicy {
    record Prepared(ActionPolicy.Action action,ActionExecutionContract.Source source,String policyBody) {
        boolean exhausted(ActionExecution row,Instant now) {
            int maximum=action.retry()==null?1:action.retry().maximumAttempts();
            return row.getAttemptCount()>=maximum || action.retry()!=null && action.retry().effectiveUntil()!=null
                    && !now.isBefore(action.retry().effectiveUntil());
        }
    }
    private ActionFrozenPolicy() { }
    static Prepared validate(ActionExecution row,ActionProductionSourceValidator.Reference reference,
                             ActionProductionSourceValidator.Validated validated,ObjectMapper json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if(validated.policy()==null)throw IqcException.invalidState("动作领取缺少原生产策略");
        var action=validated.policy().actions().stream().filter(value->Objects.equals(value.actionId(),row.getActionId())).findFirst().orElse(null);
        if(action==null)throw IqcException.invalidState("动作领取缺少冻结动作");
        String policyBody=json.writeValueAsString(validated.policy());
        var source=new ActionExecutionContract.Source(reference.taskId(),reference.executionId(),reference.conversationId(),
                reference.resultId(),validated.decisionReference(),SelectorAssetService.hash(policyBody),action.actionId(),reference.owner());
        ActionEnqueueService.requireSame(row,source,action,validated,source.idempotencyKey(action.type()),policyBody);
        if(ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)
            throw IqcException.invalidState("动作领取条件不再成立");
        if(row.getAttemptCount()==null || row.getAttemptCount()<0)throw IqcException.invalidState("动作尝试计数无效");
        return new Prepared(action,source,policyBody);
    }
}
