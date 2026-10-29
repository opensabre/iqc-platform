package io.github.opensabre.iqc.schedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.Instant;
/** Domain transition and terminal attempt fact share one transaction; the STARTED event was committed independently. */
@Service @RequiredArgsConstructor
public class TriggerAttemptRunner {
    private final TriggerAttemptService attempts;
    private final PeriodicScheduleService schedules;
    private final EventSubscriptionService subscriptions;
    private final TaskTriggerService triggers;
    private void source(TriggerAttemptService.Handle handle,TriggerAttemptService.Context expected){
        if(handle==null || !expected.equals(handle.context()))throw io.github.opensabre.iqc.governance.IqcException.invalidState("尝试与当前触发帧来源不一致");
    }
    private void taskSource(TriggerAttemptService.Handle handle,TaskTriggerService.Frame frame){
        source(handle,new TriggerAttemptService.Context(TriggerAttemptService.Kind.TASK,frame.taskId(),frame.accepted().subscriptionRevisionId(),
                frame.requestHash(),frame.accepted().request().occurredAt(),frame.accepted().ownerBound()));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public boolean periodic(TriggerAttemptService.Handle handle,PeriodicScheduleService.Frame frame,Instant now,ScheduledActorAccessService.Resolved actor){
        source(handle,new TriggerAttemptService.Context(TriggerAttemptService.Kind.PERIODIC,frame.scheduleId(),frame.revisionId(),frame.revisionHash(),frame.dueAt(),frame.snapshot().ownerBound()));
        boolean fired=schedules.fire(frame,now,actor);attempts.committed(handle,fired?1:0);return fired;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public int subscription(TriggerAttemptService.Handle handle,EventSubscriptionService.Frame frame,ScheduledActorAccessService.Resolved actor){
        source(handle,new TriggerAttemptService.Context(TriggerAttemptService.Kind.SUBSCRIPTION,frame.subscriptionId(),frame.revisionId(),frame.revisionHash(),null,frame.snapshot().ownerBound()));
        int count=subscriptions.fire(frame,actor);attempts.committed(handle,count);return count;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public boolean expire(TriggerAttemptService.Handle handle,TaskTriggerService.Frame frame,Instant now){
        taskSource(handle,frame);boolean expired=triggers.expire(frame,now);if(expired)attempts.committed(handle,1);return expired;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public boolean task(TriggerAttemptService.Handle handle,TaskTriggerService.Frame frame,ScheduledActorAccessService.Resolved actor){
        taskSource(handle,frame);boolean changed=triggers.materialize(frame,actor);attempts.committed(handle,changed?1:0);return changed;
    }
}
