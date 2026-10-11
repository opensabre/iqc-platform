package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Short local transaction: durable success and its human reason commit or roll back together. */
@Service
@RequiredArgsConstructor
public class ActionReconciliationStateService {
    private final ActionExecutionMapper actions;
    private final ActionReconciliationEventMapper journal;
    private final ActionReconciliationAttemptService attempts;

    /** Proof and owner authorization are obtained by the caller before this transaction; no adapter I/O occurs here. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public boolean confirm(ActionAdapter.Request request,long version,String token,ActionAdapter.Receipt receipt,
                           String actor,String reason,ActionReconciliationAttemptService.Attempt attempt) {
        if(reason==null || reason.isBlank() || reason.trim().length()>1000 || request==null || receipt==null)
            throw IqcException.invalidArgument("动作核对请求或原因无效");
        if(!java.util.Objects.equals(actor,request.source().owner()))throw IqcException.accessDenied("动作负责人不一致");
        if(attempt==null || !java.util.Objects.equals(attempt.actionId(),request.executionId())
                || !java.util.Objects.equals(attempt.resultId(),request.source().resultId()) || attempt.version()!=version
                || !java.util.Objects.equals(attempt.actor(),actor) || !java.util.Objects.equals(attempt.reason(),reason.trim()))
            throw IqcException.invalidState("成功核对与持久尝试不一致");
        receipt.requireRequest(request);
        if(receipt.outcome()!=ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)
            throw IqcException.invalidState("动作核对仅接受真实成功产物");
        if(version<1)throw IqcException.invalidArgument("动作核对版本无效");
        if(version==Long.MAX_VALUE)return false;
        var now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        if(actions.reconcileSuccessBound(request,version,token,now,receipt.evidenceReference(),receipt.resultReference(),actor)!=1)return false;
        // The ledger trigger has just inserted this transition. Missing/mismatched journal data must abort the CAS.
        if(journal.recordReason(request.executionId(),version+1,version,request.source().resultId(),actor,reason.trim())!=1)
            throw IqcException.invalidState("动作核对原因未能与状态同时保存");
        attempts.confirmed(attempt);
        return true;
    }
}
