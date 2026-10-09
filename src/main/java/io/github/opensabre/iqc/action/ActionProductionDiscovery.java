package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Only committed results are visible; organization calls happen before enqueue obtains locks. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="iqc.actions.enabled",havingValue="true",matchIfMissing=false)
public class ActionProductionDiscovery {
    private final ActionDiscoveryMapper discovery;
    private final ActionEnqueueService enqueue;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    @Scheduled(fixedDelayString="${iqc.actions.scan-delay-ms:5000}")
    public void tick() {
        ScheduledActorAccessService.requireBackground();
        for(var source:discovery.findDue(LocalDateTime.now(ZoneOffset.UTC))) {
            try {
                var snapshot=json.readTree(discovery.taskSnapshot(source.taskId()));
                var bound=json.treeToValue(snapshot.path("actionOwnerBound"),ScheduledActorAccessService.CreationBound.class);
                var actor=actors.resolve(bound,ActionEnqueueService.REQUIRED_GRANTS);
                var reference=new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),
                        source.conversationId(),source.resultId(),source.owner());
                scope.asScheduledActor(actor,()->enqueue.enqueue(reference,actor));
            } catch(Exception failure) {
                // Scope has been restored. No action send occurred in this discovery worker.
                discovery.defer(source.resultId(),LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
                log.warn("Action production discovery deferred: sourceResultId={}",source.resultId());
            }
        }
    }
}
