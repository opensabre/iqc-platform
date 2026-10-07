package io.github.opensabre.iqc.schedule;
import io.github.opensabre.iqc.conversation.ApiConversationInput;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.time.Instant;

public record TaskTriggerRequest(String schemaVersion,String source,String eventId,String eventType,Instant occurredAt,Instant expiresAt,
        String name,PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector,
        Target target,ApiConversationInput content,int concurrency,boolean requireASR,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy retryPolicy) {
    public record Target(String conversationId,String sourceType,String externalId,String inputRevision) { }
    public TaskTriggerRequest(String schemaVersion,String source,String eventId,String eventType,Instant occurredAt,Instant expiresAt,
            String name,PeriodicDefinition.PipelineReference pipeline,SelectorAssetService.Reference selector,Target target,ApiConversationInput content,int concurrency,boolean requireASR){
        this(schemaVersion,source,eventId,eventType,occurredAt,expiresAt,name,pipeline,selector,target,content,concurrency,requireASR,null);
    }
    public TaskTriggerRequest {
        if(!"iqc-task-trigger-v1".equals(schemaVersion) || source==null || !source.matches("[A-Za-z0-9:_-]{1,64}")
                || eventId==null || eventId.isBlank() || !eventId.equals(eventId.strip()) || eventId.length()>128 || eventId.chars().anyMatch(Character::isISOControl)
                || eventType==null || !eventType.matches("[A-Za-z0-9:_.-]{1,64}") || occurredAt==null || expiresAt==null
                || !expiresAt.isAfter(occurredAt) || name==null || name.isBlank() || name.length()>100
                || concurrency<1 || concurrency>32 || pipeline==null || pipeline.pipelineId()==null
                || !pipeline.pipelineId().matches("[A-Za-z0-9:_-]{1,64}") || pipeline.versionNo()<1
                || (target==null)==(content==null))throw new IllegalArgumentException("invalid trigger request");
        occurredAt=occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        expiresAt=expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(!expiresAt.isAfter(occurredAt))throw new IllegalArgumentException("trigger effective interval must exceed millisecond precision");
        if(content!=null && (requireASR || "ASRCompleted".equals(eventType))
                && (content.metadata()==null || !java.util.Set.of("COMPLETED","SUCCESS","SUCCEEDED").contains(
                        content.metadata().asrStatus()==null?"":content.metadata().asrStatus().toUpperCase(java.util.Locale.ROOT).trim())))
            throw new IllegalArgumentException("submitted ASR content must already be complete");
        if(selector!=null && (selector.selectorId()==null || !selector.selectorId().matches("[A-Za-z0-9:_-]{1,64}") || selector.versionNo()<1))
            throw new IllegalArgumentException("invalid trigger selector version");
        if(target!=null && (target.inputRevision()==null || !target.inputRevision().matches("[a-f0-9]{64}")
                || (target.conversationId()==null)==(target.externalId()==null)
                || (target.conversationId()!=null && (target.sourceType()!=null || !target.conversationId().matches("[A-Za-z0-9:_-]{1,64}")))
                || (target.externalId()!=null && (target.externalId().isBlank() || target.externalId().length()>128
                    || target.sourceType()==null || !target.sourceType().matches("[A-Z][A-Z0-9_-]{0,31}")))))
            throw new IllegalArgumentException("invalid trigger input revision");
    }
}
