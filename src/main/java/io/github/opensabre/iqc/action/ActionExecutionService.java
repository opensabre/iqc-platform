package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** One claimed attempt. A scheduler must resolve the owner first; this is not a public send API. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActionExecutionService {
    public enum Dispatch {NOT_CLAIMED,SETTLED,STALE_RECEIPT,SETTLEMENT_PENDING}
    private final ActionClaimService claims;
    private final ActionSettlementService settlements;
    private final ActionAdapterRegistry adapters;
    private final ActionExecutionMapper executions;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    /** NEVER rejects callers inside an analysis transaction rather than suspending their still-held locks. */
    @Transactional(propagation=Propagation.NEVER)
    public Dispatch execute(String id,long observedVersion,ScheduledActorAccessService.Resolved actor) {
        var claim=claims.claim(id,observedVersion,actor);
        if(claim==null)return Dispatch.NOT_CLAIMED;
        ActionAdapter.Receipt receipt;
        try {
            var request=claim.request();var source=request.source();
            ActionEnqueueService.requireActor(new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),
                    source.conversationId(),source.resultId(),source.owner()),actor,scope);
            var current=executions.selectById(id);
            if(current==null || !"RUNNING".equals(current.getStatus()) || !Objects.equals(current.getVersion(),claim.version())
                    || !Objects.equals(current.getClaimToken(),claim.token()))return Dispatch.STALE_RECEIPT;
            var policy=json.readValue(request.policySnapshotJson(),ActionPolicy.class);
            var action=policy.actions().stream().filter(value->source.actionId().equals(value.actionId())).findFirst().orElse(null);
            if(action==null)throw IqcException.invalidState("执行缺少冻结动作");
            var binding=adapters.require(action);
            if(!binding.definition().equals(request.adapter()))throw IqcException.invalidState("执行适配协议发生变化");
            ActionEnqueueService.requireFresh(actor);
            receipt=binding.adapter().execute(request);
            if(receipt==null)throw IqcException.invalidState("动作适配器未返回回执");
            receipt.requireRequest(request);
        } catch(Exception unconfirmed) {
            // Exception absence/presence does not prove whether an external effect occurred.
            receipt=new ActionAdapter.Receipt(claim.request().idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,
                    null,null,"ADAPTER_CONFIRMATION_UNKNOWN");
        }
        try {
            return settlements.settle(claim.request(),claim.version(),claim.token(),receipt)?Dispatch.SETTLED:Dispatch.STALE_RECEIPT;
        } catch(RuntimeException unavailable) {
            // Never call execute again here. A lost persisted acknowledgement requires reconciliation.
            log.warn("Action receipt settlement pending: actionExecutionId={}",id);
            return Dispatch.SETTLEMENT_PENDING;
        }
    }
}
