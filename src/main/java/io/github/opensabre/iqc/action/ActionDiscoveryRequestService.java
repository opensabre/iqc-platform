package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** User entry requests discovery only. Organization identity lookup is outside result locks. */
@Service @RequiredArgsConstructor
public class ActionDiscoveryRequestService {
    private final ScheduledActorAccessService actors;
    private final ActionDiscoveryRequestStateService states;
    @Value("${iqc.actions.enabled:false}") private boolean enabled;
    @Transactional(propagation=Propagation.NEVER)
    public ActionDiscoveryRequestStateService.Response request(String resultId) {
        if(!enabled)throw IqcException.invalidState("动作后台处理尚未启用");
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}"))
            throw IqcException.invalidArgument("动作来源结果标识无效");
        return states.request(resultId,actors.capture());
    }
}
