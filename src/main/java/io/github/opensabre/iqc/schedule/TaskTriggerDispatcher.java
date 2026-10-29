package io.github.opensabre.iqc.schedule;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(prefix="iqc.trigger",name="enabled",havingValue="true")
public class TaskTriggerDispatcher {
    private final TaskTriggerService triggers;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final TriggerAttemptService attempts;
    private final TriggerAttemptRunner runner;
    @Scheduled(fixedDelayString="${iqc.trigger.delay-ms:5000}")
    public synchronized void discover(){
        for(String id:triggers.waiting(Instant.now())) {
            TaskTriggerService.Frame frame=null;
            TriggerAttemptService.Handle attempt=null;
            TriggerAttemptService.FailureStage stage=TriggerAttemptService.FailureStage.AUTHORIZATION;
            try{
                frame=triggers.peek(id);if(frame==null)continue;
                attempt=attempts.begin(new TriggerAttemptService.Context(TriggerAttemptService.Kind.TASK,id,frame.accepted().subscriptionRevisionId(),frame.requestHash(),frame.accepted().request().occurredAt(),frame.accepted().ownerBound()));
                stage=TriggerAttemptService.FailureStage.EXPIRATION;
                if(runner.expire(attempt,frame,Instant.now()))continue;
                stage=TriggerAttemptService.FailureStage.AUTHORIZATION;
                var actor=actors.resolve(frame.accepted().ownerBound(),TaskTriggerService.grants(frame.accepted().request()));var captured=frame;var currentAttempt=attempt;
                stage=TriggerAttemptService.FailureStage.MATERIALIZATION;
                scope.asScheduledActor(actor,()->runner.task(currentAttempt,captured,actor));
            }catch(RuntimeException failure){
                if(attempt!=null)try{attempts.failed(attempt,stage,failure);}catch(RuntimeException historyFailure){log.error("event=iqc_trigger_attempt_finish attemptId={}",attempt.attemptId(),historyFailure);}
                if(frame!=null)try{triggers.defer(frame);}catch(RuntimeException deferFailure){log.error("event=iqc_trigger_defer taskId={}",id,deferFailure);}
                log.error("event=iqc_trigger_materialize taskId={} errorType={}",id,failure.getClass().getSimpleName(),failure);
            }
        }
    }
}
