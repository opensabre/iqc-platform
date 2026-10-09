package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replayable cyclic discovery: no external call holds an analysis transaction or a database cursor. */
@Component
@Slf4j
@ConditionalOnProperty(name="iqc.actions.enabled",havingValue="true",matchIfMissing=false)
public class ActionSourceDiscovery {
    private final ActionSourceDiscoveryMapper sources;
    private final InspectionTaskMapper tasks;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ActionEnqueueService enqueue;
    private final ObjectMapper json;
    private String after="";
    public ActionSourceDiscovery(ActionSourceDiscoveryMapper sources,InspectionTaskMapper tasks,
            ScheduledActorAccessService actors,IqcDataScope scope,ActionEnqueueService enqueue,ObjectMapper json) {
        this.sources=sources;this.tasks=tasks;this.actors=actors;this.scope=scope;this.enqueue=enqueue;this.json=json;
    }
    @Scheduled(fixedDelayString="${iqc.actions.source-scan-delay-ms:5000}")
    public synchronized void tick() {
        ScheduledActorAccessService.requireBackground();
        var page=sources.findAfter(after);
        if(page.isEmpty()){after="";return;}
        for(var source:page) {
            // Advancing independently of match/access failure prevents a blocked first page starving later sources.
            after=source.id();
            try {
                var task=tasks.selectById(source.taskId());
                if(task==null || task.getRuleSnapshotJson()==null)continue;
                var snapshot=json.readTree(task.getRuleSnapshotJson());
                // Policy interpretation remains in the published source validator; identity is never inferred.
                if(!snapshot.hasNonNull("actionOwnerBound"))continue;
                var bound=json.treeToValue(snapshot.get("actionOwnerBound"),ScheduledActorAccessService.CreationBound.class);
                var actor=actors.resolve(bound,ActionEnqueueService.REQUIRED_GRANTS);
                var reference=new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),source.conversationId(),source.id(),source.owner());
                scope.asScheduledActor(actor,()->enqueue.enqueue(reference,actor));
            } catch(Exception unavailable) {
                log.warn("Action source discovery deferred: resultId={}",source.id());
            }
        }
    }
}
