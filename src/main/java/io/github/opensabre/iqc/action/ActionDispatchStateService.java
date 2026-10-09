package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Background-only diagnostics. No dispatch or network, and every write fences the observed version. */
@Service
@RequiredArgsConstructor
public class ActionDispatchStateService {
    private final ActionExecutionMapper executions;
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean blockReady(ActionExecution observed,String status,String code) {
        ScheduledActorAccessService.requireBackground();validate(observed);
        if(!java.util.Set.of("BLOCKED_SOURCE","BLOCKED_AUTHORIZATION").contains(status)
                || code==null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))throw IqcException.invalidArgument("动作后台阻塞原因无效");
        return executions.blockReady(observed.getId(),observed.getVersion(),status,code,utc(Instant.now()))==1;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean isolateLost(ActionExecution observed,Instant cutoff) {
        ScheduledActorAccessService.requireBackground();validate(observed);
        Instant now=Instant.now();
        if(cutoff==null || cutoff.isAfter(now) || observed.getClaimToken()==null || !observed.getClaimToken().matches("[a-f0-9]{32}"))
            throw IqcException.invalidArgument("动作失联领取证据无效");
        return executions.quarantineLostClaim(observed.getId(),observed.getVersion(),observed.getClaimToken(),utc(cutoff),utc(now))==1;
    }
    private void validate(ActionExecution row) {
        if(row==null || row.getId()==null || !row.getId().matches("[a-f0-9]{64}")
                || row.getVersion()==null || row.getVersion()<0 || row.getVersion()==Long.MAX_VALUE)
            throw IqcException.invalidArgument("动作后台观察标识或版本无效");
    }
    private LocalDateTime utc(Instant instant){return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
}
