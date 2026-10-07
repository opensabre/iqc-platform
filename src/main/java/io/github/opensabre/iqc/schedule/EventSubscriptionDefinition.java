package io.github.opensabre.iqc.schedule;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;
public record EventSubscriptionDefinition(String schemaVersion,String eventType,Instant effectiveFrom,Instant effectiveUntil,
        int waitSeconds,int concurrency,PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy retryPolicy) {
    public static final String SCHEMA="iqc-event-subscription-v1";
    public EventSubscriptionDefinition(String schemaVersion,String eventType,Instant effectiveFrom,Instant effectiveUntil,
            int waitSeconds,int concurrency,PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector){
        this(schemaVersion,eventType,effectiveFrom,effectiveUntil,waitSeconds,concurrency,pipeline,selector,null);
    }
    public EventSubscriptionDefinition {
        if(!SCHEMA.equals(schemaVersion) || (eventType==null || !java.util.Set.of("ConversationCompleted","ASRCompleted").contains(eventType))
                || effectiveFrom==null || waitSeconds<1 || waitSeconds>604800 || concurrency<1 || concurrency>32
                || pipeline==null || pipeline.pipelineId()==null || !pipeline.pipelineId().matches("[A-Za-z0-9:_-]{1,64}") || pipeline.versionNo()<1)
            throw new IllegalArgumentException("invalid event subscription definition");
        effectiveFrom=effectiveFrom.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if(effectiveUntil!=null)effectiveUntil=effectiveUntil.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant min=Instant.parse("1000-01-01T00:00:00Z"),max=Instant.parse("9999-12-31T23:59:59Z");
        if(effectiveFrom.isBefore(min) || effectiveFrom.isAfter(max) || (effectiveUntil!=null &&
                (!effectiveUntil.isAfter(effectiveFrom) || effectiveUntil.isAfter(max))))throw new IllegalArgumentException("invalid subscription effective interval");
        if(selector!=null && (selector.selectorId()==null || !selector.selectorId().matches("[A-Za-z0-9:_-]{1,64}") || selector.versionNo()<1))
            throw new IllegalArgumentException("invalid subscription selector");
    }
}
