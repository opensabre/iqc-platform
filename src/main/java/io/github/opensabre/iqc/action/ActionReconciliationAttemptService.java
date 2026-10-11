package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Durable observation boundary. Callers authorize source and stable identity before starting. */
@Service @RequiredArgsConstructor
public class ActionReconciliationAttemptService {
    public record Attempt(String id,String actionId,String resultId,long version,String actor,String userId,String reason) { }
    public enum Outcome { UNCONFIRMED, STALE_OBSERVATION, LOOKUP_FAILED }
    private final ActionReconciliationAttemptMapper attempts;

    /** Commit STARTED before proof lookup; failure must prevent the caller from invoking an adapter. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Attempt start(String actionId,String resultId,long version,String actor,String userId,String reason) {
        if(!reference(actionId) || !reference(resultId) || version<1 || !identity(actor) || !identity(userId)
                || reason==null || reason.isBlank() || reason.trim().length()>1000)
            throw IqcException.invalidArgument("核对尝试标识、身份或原因无效");
        var attempt=new Attempt(UUID.randomUUID().toString().replace("-",""),actionId,resultId,version,actor,userId,reason.trim());
        if(attempts.start(attempt.id(),actionId,resultId,version,actor,userId,attempt.reason(),now())!=1)
            throw IqcException.invalidState("核对尝试未能持久开始");
        return attempt;
    }

    /** A no-state-change result has its own short commit, never another action claim. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(Attempt attempt,Outcome outcome) {
        if(attempt==null || outcome==null)throw IqcException.invalidArgument("核对尝试终态无效");
        write(attempt,outcome.name(),null);
    }

    /** Must join the existing successful ledger/event transaction; cannot independently assert success. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirmed(Attempt attempt) {
        if(attempt==null || attempt.version()<1 || attempt.version()==Long.MAX_VALUE)
            throw IqcException.invalidArgument("核对尝试成功版本无效");
        write(attempt,"CONFIRMED",attempt.version()+1);
    }

    private void write(Attempt a,String state,Long version) {
        if(attempts.finish(a.id(),a.actionId(),a.resultId(),a.version(),a.actor(),a.userId(),a.reason(),state,version,now())!=1)
            throw IqcException.invalidState("核对尝试终态未能持久保存");
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
    private static boolean reference(String value) { return value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}"); }
    private static boolean identity(String value) { return value!=null && !value.isBlank() && value.length()<=128; }
}
