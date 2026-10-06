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
    @Scheduled(fixedDelayString="${iqc.trigger.delay-ms:5000}")
    public synchronized void discover(){
        for(String id:triggers.waiting(Instant.now())) {
            TaskTriggerService.Frame frame=null;
            try{
                frame=triggers.peek(id);if(frame==null)continue;
                if(triggers.expire(frame,Instant.now()))continue;
                var actor=actors.resolve(frame.accepted().ownerBound(),TaskTriggerService.grants(frame.accepted().request()));var captured=frame;
                scope.asScheduledActor(actor,()->triggers.materialize(captured,actor));
            }catch(RuntimeException failure){
                if(frame!=null)try{triggers.defer(frame);}catch(RuntimeException deferFailure){log.error("event=iqc_trigger_defer taskId={}",id,deferFailure);}
                log.error("event=iqc_trigger_materialize taskId={} errorType={}",id,failure.getClass().getSimpleName(),failure);
            }
        }
    }
}
