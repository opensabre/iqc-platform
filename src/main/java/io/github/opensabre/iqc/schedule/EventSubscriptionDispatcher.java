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
    private final TriggerAttemptService attempts;
    private final TriggerAttemptRunner runner;
    @Scheduled(fixedDelayString="${iqc.subscription.delay-ms:5000}")
    public synchronized void discover(){
        Instant now=Instant.now();
        for(String id:subscriptions.due(now)){
            EventSubscriptionService.Frame frame=null;
            TriggerAttemptService.Handle attempt=null;
            TriggerAttemptService.FailureStage stage=TriggerAttemptService.FailureStage.AUTHORIZATION;
            try{
                frame=subscriptions.peek(id,now);if(frame==null)continue;
                attempt=attempts.begin(new TriggerAttemptService.Context(TriggerAttemptService.Kind.SUBSCRIPTION,id,frame.revisionId(),frame.revisionHash(),null,frame.snapshot().ownerBound()));
                var actor=actors.resolve(frame.snapshot().ownerBound(),EventSubscriptionService.grants());var current=frame;var currentAttempt=attempt;
                stage=TriggerAttemptService.FailureStage.MATERIALIZATION;
                scope.asScheduledActor(actor,()->runner.subscription(currentAttempt,current,actor));
            }catch(RuntimeException failure){
                if(attempt!=null)try{attempts.failed(attempt,stage,failure);}catch(RuntimeException historyFailure){log.error("event=iqc_trigger_attempt_finish attemptId={}",attempt.attemptId(),historyFailure);}
                try{if(frame!=null)subscriptions.defer(frame,Instant.now());else subscriptions.deferUnreadable(id,Instant.now());}catch(RuntimeException deferFailure){log.error("event=iqc_subscription_defer subscriptionId={}",id,deferFailure);}
                log.error("event=iqc_subscription_discovery subscriptionId={} errorType={}",id,failure.getClass().getSimpleName(),failure);
            }
        }
    }
}
