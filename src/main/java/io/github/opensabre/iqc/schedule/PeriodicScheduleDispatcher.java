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
                try {
                    frame=schedules.peek(id,now);if(frame==null){iterator.remove();continue;}
                    var definition=frame.snapshot().definition();
                    int allowance=definition.misfirePolicy()==PeriodicDefinition.MisfirePolicy.CATCH_UP?definition.maximumCatchUp():1;
                    if(entry.getValue()>=allowance){iterator.remove();continue;}
                    var actor=actors.resolve(frame.snapshot().ownerBound());var captured=frame;
                    boolean fired=scope.asScheduledActor(actor,()->schedules.fire(captured,now,actor));
                    if(!fired){iterator.remove();continue;}
                    count++;entry.setValue(entry.getValue()+1);
                    if(entry.getValue()>=allowance)iterator.remove();
                } catch(RuntimeException failure) {
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
