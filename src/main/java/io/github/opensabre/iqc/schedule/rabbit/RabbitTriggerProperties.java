package io.github.opensabre.iqc.schedule.rabbit;

import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.schedule.TaskTriggerRequest;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trusted deployment configuration; no principal or scope is accepted from a message. */
@Getter @Setter
@ConfigurationProperties("iqc.trigger.rabbit")
public class RabbitTriggerProperties {
    private boolean enabled;
    private String uri;
    private String queue="iqc.trigger.events";
    private String source="business-events";
    private String ownerUserId;
    private String owner;
    private String groupId;
    private boolean all;
    private Set<String> pipelineIds=Set.of();
    private Set<String> eventTypes=Set.of("ComplaintCreated","OrderCompleted","RiskDetected");
    private boolean allowContent;
    public void validate(){
        if(uri==null||uri.isBlank()||queue==null||!queue.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                ||source==null||!source.matches("[A-Za-z0-9:_-]{1,64}")||"archive-subscription".equals(source)
                ||ownerUserId==null||!ownerUserId.matches("[A-Za-z0-9:_-]{1,64}")||owner==null||owner.isBlank()
                ||owner.length()>128||!owner.equals(owner.strip())||owner.codePoints().anyMatch(Character::isISOControl)||"system".equals(owner)
                ||groupId!=null&&!groupId.matches("[A-Za-z0-9:_-]{1,64}")
                ||pipelineIds==null||pipelineIds.isEmpty()||pipelineIds.size()>100||pipelineIds.stream().anyMatch(v->v==null||!v.matches("[A-Za-z0-9:_-]{1,64}"))
                ||eventTypes==null||eventTypes.isEmpty()||eventTypes.size()>100||eventTypes.stream().anyMatch(v->v==null||!v.matches("[A-Za-z0-9:_.-]{1,64}")))
            throw new IllegalStateException("IQC Rabbit trigger requires a valid deployment identity and explicit allowed assets");
    }
    public ScheduledActorAccessService.CreationBound bound(){
        return new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1",ownerUserId,owner,all,groupId);
    }
    public boolean permits(TaskTriggerRequest request){
        return source.equals(request.source())&&pipelineIds.contains(request.pipeline().pipelineId())
                &&eventTypes.contains(request.eventType())&&(allowContent||request.content()==null);
    }
}
