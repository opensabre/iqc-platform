package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** The existing durable worker is the only scheduler; organization requests run outside SQL locks. */
@Service @RequiredArgsConstructor @Slf4j
public class BulkTaskCoordinator {
    private final BulkTaskLifecycleService lifecycle;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;

    /** Withdrawal never requires a model worker slot or a current create grant. */
    public boolean controlRequested(String taskId,String executionId) {
        return lifecycle.controlIfRequested(taskId,executionId);
    }

    public void executePersisted(String taskId,String executionId) {
        try {
            if(controlRequested(taskId,executionId))return;
            var bound=lifecycle.ownerForDispatch(taskId,executionId);
            var actor=actors.resolve(bound,BulkTaskMaterializationService.GRANTS);
            scope.asScheduledActor(actor,()->{
                if(!lifecycle.claim(taskId,executionId))return null;
                lifecycle.tick(taskId,executionId);return null;
            });
        } catch(RuntimeException failure) {
            lifecycle.parkAfterFailure(taskId,executionId);
            log.error("event=iqc_bulk_tick_failed taskId={} executionId={} errorType={}",taskId,executionId,failure.getClass().getSimpleName(),failure);
        }
    }
}
