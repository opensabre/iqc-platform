package io.github.opensabre.iqc.schedule;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;
public record EventSubscriptionDefinition(String schemaVersion,String eventType,Instant effectiveFrom,Instant effectiveUntil,
        int waitSeconds,int concurrency,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy retryPolicy,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        PeriodicDefinition.RolloutReference rollout) {
    public static final String SCHEMA="iqc-event-subscription-v1";
    public static final String ROLLOUT_SCHEMA="iqc-event-subscription-rollout-v1";
    public boolean usesRollout(){return ROLLOUT_SCHEMA.equals(schemaVersion);}
    public String targetPipelineId(){return usesRollout()?rollout.pipelineId():pipeline.pipelineId();}
    public EventSubscriptionDefinition(String schemaVersion,String eventType,Instant effectiveFrom,Instant effectiveUntil,
            int waitSeconds,int concurrency,PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector){
        this(schemaVersion,eventType,effectiveFrom,effectiveUntil,waitSeconds,concurrency,pipeline,selector,null);
    }
    public EventSubscriptionDefinition(String schemaVersion,String eventType,Instant effectiveFrom,Instant effectiveUntil,
            int waitSeconds,int concurrency,PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector,
            io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy retryPolicy){
        this(schemaVersion,eventType,effectiveFrom,effectiveUntil,waitSeconds,concurrency,pipeline,selector,retryPolicy,null);
    }
    public EventSubscriptionDefinition {
        boolean gray=ROLLOUT_SCHEMA.equals(schemaVersion);
        if((!SCHEMA.equals(schemaVersion)&&!gray) || (eventType==null || !java.util.Set.of("ConversationCompleted","ASRCompleted").contains(eventType))
                || effectiveFrom==null || waitSeconds<1 || waitSeconds>604800 || concurrency<1 || concurrency>32
                || (gray?(pipeline!=null || rollout==null || rollout.pipelineId()==null)
                        :(pipeline==null || pipeline.pipelineId()==null || pipeline.versionNo()<1 || rollout!=null)))
            throw new IllegalArgumentException("invalid event subscription definition");
        if(!(gray?rollout.pipelineId():pipeline.pipelineId()).matches("[A-Za-z0-9:_-]{1,64}"))
            throw new IllegalArgumentException("invalid subscription pipeline identity");
        effectiveFrom=effectiveFrom.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if(effectiveUntil!=null)effectiveUntil=effectiveUntil.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant min=Instant.parse("1000-01-01T00:00:00Z"),max=Instant.parse("9999-12-31T23:59:59Z");
        if(effectiveFrom.isBefore(min) || effectiveFrom.isAfter(max) || (effectiveUntil!=null &&
                (!effectiveUntil.isAfter(effectiveFrom) || effectiveUntil.isAfter(max))))throw new IllegalArgumentException("invalid subscription effective interval");
        if(selector!=null && (selector.selectorId()==null || !selector.selectorId().matches("[A-Za-z0-9:_-]{1,64}") || selector.versionNo()<1))
            throw new IllegalArgumentException("invalid subscription selector");
    }
}
