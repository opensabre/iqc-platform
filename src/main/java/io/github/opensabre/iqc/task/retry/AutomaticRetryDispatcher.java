package io.github.opensabre.iqc.task.retry;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="iqc.retry.enabled",havingValue="true",matchIfMissing=false)
public class AutomaticRetryDispatcher {
    private final AutomaticRetryService retries;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final TriggerAttemptService attempts;
    @Scheduled(fixedDelayString="${iqc.retry.scan-delay-ms:5000}")
    public void tick(){
        ScheduledActorAccessService.requireBackground();
        for(String id:retries.waiting(Instant.now())){
            AutomaticRetryService.Frame frame=null;TriggerAttemptService.Handle handle=null;
            var stage=TriggerAttemptService.FailureStage.AUTHORIZATION;
            try{
                frame=retries.peek(id,Instant.now());if(frame==null)continue;
                handle=attempts.begin(new TriggerAttemptService.Context(TriggerAttemptService.Kind.TASK,id,frame.executionId(),
                        frame.policyHash(),frame.retryAt(),frame.envelope().ownerBound()));
                var actor=actors.resolve(frame.envelope().ownerBound(),frame.envelope().requiredGrants());
                stage=TriggerAttemptService.FailureStage.MATERIALIZATION;
                var observed=frame;var attempt=handle;scope.asScheduledActor(actor,()->retries.fire(observed,actor,attempt));
            }catch(RuntimeException failure){
                if(handle!=null)try{attempts.failed(handle,stage,failure);}catch(RuntimeException appendFailure){log.warn("Retry attempt completion unavailable task={} type={}",id,appendFailure.getClass().getSimpleName());}
                try{if(frame==null)retries.deferMalformed(id,Instant.now());else retries.defer(frame,Instant.now());}
                catch(RuntimeException deferFailure){log.warn("Retry defer unavailable task={} type={}",id,deferFailure.getClass().getSimpleName());}
                log.warn("Automatic retry deferred task={} type={}",id,failure.getClass().getSimpleName());
            }
        }
    }
}
