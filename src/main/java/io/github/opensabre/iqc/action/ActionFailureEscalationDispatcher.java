package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;

/** Advisory bounded sweep; committed review requests provide restart/concurrency idempotency. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="iqc.actions.enabled",havingValue="true",matchIfMissing=false)
public class ActionFailureEscalationDispatcher {
    private final ActionExecutionMapper actions;
    private final ActionDispatcher dispatcher;
    private final ActionFailureEscalationService escalation;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    // This cursor is only an optimization: no source or completion proof depends on it.
    private String after;

    @Scheduled(fixedDelayString="${iqc.actions.scan-delay-ms:5000}")
    public synchronized void tick() {
        ScheduledActorAccessService.requireBackground();
        var page=actions.findConfirmedFailures(after);
        for(var observed:page) {
            try {
                var row=actions.selectById(observed.getId());
                if(row==null || !Objects.equals(row.getVersion(),observed.getVersion()) || !"FAILED".equals(row.getStatus()))continue;
                // Validate the existing frozen identity/hash before reading its policy.
                var bound=dispatcher.ownerBound(row);
                var policy=json.readValue(row.getPolicySnapshotJson(),ActionPolicy.class);
                var action=policy.actions().stream().filter(value->value.actionId().equals(row.getActionId())).findFirst().orElse(null);
                if(action==null || action.failureHandling()!=ActionPolicy.FailureHandling.ESCALATE_REVIEW)continue;
                var resources=new java.util.HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);
                resources.add("iqc:review:create");
                var actor=actors.resolve(bound,Set.copyOf(resources));
                scope.asScheduledActor(actor,()->escalation.escalate(row.getId(),row.getVersion()));
            } catch(Exception unavailable) {
                // No arbitrary exception payload, receipt rewrite, send or permanent skip.
                log.warn("Action failure escalation unavailable: actionExecutionId={}",observed.getId());
            }
        }
        after=page.size()<100?null:page.get(page.size()-1).getId();
    }
}
