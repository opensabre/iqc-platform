package io.github.opensabre.iqc.schedule;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(prefix="iqc.subscription",name="enabled",havingValue="true")
public class EventSubscriptionDispatcher {
    private final EventSubscriptionService subscriptions;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    @Scheduled(fixedDelayString="${iqc.subscription.delay-ms:5000}")
    public synchronized void discover(){
        Instant now=Instant.now();
        for(String id:subscriptions.due(now)){
            EventSubscriptionService.Frame frame=null;
            try{
                frame=subscriptions.peek(id,now);if(frame==null)continue;
                var actor=actors.resolve(frame.snapshot().ownerBound(),EventSubscriptionService.grants());var current=frame;
                scope.asScheduledActor(actor,()->subscriptions.fire(current,actor));
            }catch(RuntimeException failure){
                try{if(frame!=null)subscriptions.defer(frame,Instant.now());else subscriptions.deferUnreadable(id,Instant.now());}catch(RuntimeException deferFailure){log.error("event=iqc_subscription_defer subscriptionId={}",id,deferFailure);}
                log.error("event=iqc_subscription_discovery subscriptionId={} errorType={}",id,failure.getClass().getSimpleName(),failure);
            }
        }
    }
}
