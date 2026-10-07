package io.github.opensabre.iqc.schedule;

import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(prefix="iqc.schedule",name="enabled",havingValue="true")
public class PeriodicScheduleDispatcher {
    private final PeriodicScheduleService schedules;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final TriggerAttemptService attempts;
    private final TriggerAttemptRunner runner;
    @Scheduled(fixedDelayString="${iqc.schedule.delay-ms:5000}")
    public synchronized void discover(){
        Instant now=Instant.now();int count=0;
        var active=new java.util.LinkedHashMap<String,Integer>();
        schedules.due(now).forEach(id->active.put(id,0));
        // Visit each due schedule once before allowing an older schedule another catch-up tick.
        while(!active.isEmpty() && count<50) {
            var iterator=active.entrySet().iterator();
            while(iterator.hasNext() && count<50) {
                var entry=iterator.next();String id=entry.getKey();
                PeriodicScheduleService.Frame frame=null;
                TriggerAttemptService.Handle attempt=null;
            TriggerAttemptService.FailureStage stage=TriggerAttemptService.FailureStage.AUTHORIZATION;
                try {
                    frame=schedules.peek(id,now);if(frame==null){iterator.remove();continue;}
                    var definition=frame.snapshot().definition();
                    int allowance=definition.misfirePolicy()==PeriodicDefinition.MisfirePolicy.CATCH_UP?definition.maximumCatchUp():1;
                    if(entry.getValue()>=allowance){iterator.remove();continue;}
                    attempt=attempts.begin(new TriggerAttemptService.Context(TriggerAttemptService.Kind.PERIODIC,id,frame.revisionId(),frame.revisionHash(),frame.dueAt(),frame.snapshot().ownerBound()));
                    var actor=actors.resolve(frame.snapshot().ownerBound());var captured=frame;var currentAttempt=attempt;
                    stage=TriggerAttemptService.FailureStage.MATERIALIZATION;
                    boolean fired=scope.asScheduledActor(actor,()->runner.periodic(currentAttempt,captured,now,actor));
                    if(!fired){iterator.remove();continue;}
                    count++;entry.setValue(entry.getValue()+1);
                    if(entry.getValue()>=allowance)iterator.remove();
                } catch(RuntimeException failure) {
                    if(attempt!=null)try{attempts.failed(attempt,stage,failure);}catch(RuntimeException historyFailure){log.error("event=iqc_trigger_attempt_finish attemptId={}",attempt.attemptId(),historyFailure);}
                    if(frame!=null) {
                        try{schedules.defer(frame,Instant.now());}
                        catch(RuntimeException deferFailure){log.error("event=iqc_periodic_defer scheduleId={}",id,deferFailure);}
                    }
                    log.error("event=iqc_periodic_dispatch scheduleId={} errorType={}",id,failure.getClass().getSimpleName(),failure);
                    iterator.remove();
                }
            }
        }
    }
}
