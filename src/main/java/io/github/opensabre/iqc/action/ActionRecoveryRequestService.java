package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Manual authorization and organization I/O outside the recovery state transaction. */
@Service @RequiredArgsConstructor
public class ActionRecoveryRequestService {
    public record Response(String id,String state) { }
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ActionRecoveryService states;
    @Value("${iqc.actions.enabled:false}") private boolean enabled;
    @Transactional(propagation=Propagation.NEVER)
    public Response recover(String resultId,String id,long version,String reason) {
        if(!enabled)throw IqcException.invalidState("动作后台处理尚未启用");
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}")
                || id==null || !id.matches("[a-f0-9]{64}") || version<0 || version>Long.MAX_VALUE-3
                || reason==null || reason.isBlank() || reason.trim().length()>1000)
            throw IqcException.invalidArgument("动作恢复标识、版本或原因无效");
        var required=new java.util.HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);required.add(ActionRecoveryService.RESOURCE);
        var actor=actors.resolveCurrent(actors.capture(),java.util.Set.copyOf(required));
        boolean recovered=scope.asCurrentActor(actor,()->states.recover(resultId,id,version,actor,reason));
        return new Response(id,recovered?"RECOVERED":"NOT_RECOVERED");
    }
}
