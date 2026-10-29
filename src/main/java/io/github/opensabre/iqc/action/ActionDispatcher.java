package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Existing owner resolver and actor scope are reused; no caller-supplied grants or inherited bearer tokens. */
@Component
@Slf4j
@ConditionalOnProperty(name="iqc.actions.enabled",havingValue="true",matchIfMissing=false)
public class ActionDispatcher {
    private final ActionExecutionMapper executions;
    private final ActionDispatchStateService states;
    private final ActionExecutionService runner;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private final long leaseSeconds;
    public ActionDispatcher(ActionExecutionMapper executions,ActionDispatchStateService states,ActionExecutionService runner,
                            ScheduledActorAccessService actors,IqcDataScope scope,ObjectMapper json,
                            @Value("${iqc.actions.claim-lease-seconds:900}") long leaseSeconds) {
        if(leaseSeconds<60 || leaseSeconds>86400)throw new IllegalArgumentException("Action claim lease out of range");
        this.executions=executions;this.states=states;this.runner=runner;this.actors=actors;this.scope=scope;this.json=json;this.leaseSeconds=leaseSeconds;
    }
    @Scheduled(fixedDelayString="${iqc.actions.scan-delay-ms:5000}")
    public void tick() {
        ScheduledActorAccessService.requireBackground();
        Instant cutoff=Instant.now().minusSeconds(leaseSeconds);
        for(var row:executions.findLost(LocalDateTime.ofInstant(cutoff,ZoneOffset.UTC))) {
            try {states.isolateLost(row,cutoff);}
            catch(RuntimeException failure){log.warn("Action lost-claim isolation unavailable: actionExecutionId={}",row.getId());}
        }
        for(var observed:executions.findDue(LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC))) {
            String blocked="BLOCKED_SOURCE",code="ACTION_SOURCE_UNAVAILABLE";
            try {
                var row=executions.selectById(observed.getId());
                if(row==null || !Objects.equals(row.getVersion(),observed.getVersion())
                        || !("PENDING".equals(row.getStatus()) || "WAITING_RETRY".equals(row.getStatus())))continue;
                var bound=ownerBound(row);
                blocked="BLOCKED_AUTHORIZATION";code="ACTION_OWNER_ACCESS_UNAVAILABLE";
                var actor=actors.resolve(bound,ActionEnqueueService.REQUIRED_GRANTS);
                blocked="BLOCKED_SOURCE";code="ACTION_PREFLIGHT_UNAVAILABLE";
                scope.asScheduledActor(actor,()->runner.execute(row.getId(),row.getVersion(),actor));
            } catch(Exception failure) {
                // The scope callback restores context before this background-only state write.
                try {states.blockReady(observed,blocked,code);}
                catch(RuntimeException unavailable){log.warn("Action preflight blockage unavailable: actionExecutionId={}",observed.getId());}
                log.warn("Action dispatch preflight blocked: actionExecutionId={}",observed.getId());
            }
        }
    }
    ScheduledActorAccessService.CreationBound ownerBound(ActionExecution row) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(row.getInputSnapshotJson()==null || !Objects.equals(SelectorAssetService.hash(row.getInputSnapshotJson()),row.getInputSnapshotHash())
                || row.getPolicySnapshotJson()==null || !Objects.equals(SelectorAssetService.hash(row.getPolicySnapshotJson()),row.getActionPolicyHash()))
            throw IqcException.invalidState("动作分派冻结摘要不一致");
        var input=json.readTree(row.getInputSnapshotJson());
        if(input==null || !"iqc-action-production-input-v1".equals(input.path("schemaVersion").asText())
                || !Objects.equals(row.getOwner(),input.path("owner").asText()) || !Objects.equals(row.getTaskId(),input.path("taskId").asText())
                || !Objects.equals(row.getExecutionId(),input.path("executionId").asText())
                || !Objects.equals(row.getConversationId(),input.path("conversationId").asText())
                || !Objects.equals(row.getSourceResultId(),input.path("resultId").asText()))
            throw IqcException.invalidState("动作分派生产输入归属不一致");
        var task=json.readTree(input.path("taskSnapshotJson").asText());
        var bound=json.treeToValue(task.path("actionOwnerBound"),ScheduledActorAccessService.CreationBound.class);
        if(bound==null || !Objects.equals(row.getOwner(),bound.owner()))throw IqcException.invalidState("动作分派负责人不一致");
        return bound;
    }
}
