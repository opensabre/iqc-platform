package io.github.opensabre.iqc.pipelineasset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.dao.PipelineAssetVersionMapper;
import io.github.opensabre.iqc.pipelineasset.dao.PipelineRolloutRevisionMapper;
import io.github.opensabre.iqc.pipelineasset.model.PipelineAssetVersion;
import io.github.opensabre.iqc.pipelineasset.model.PipelineRolloutRevision;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Read-only original-source verification, usable by workers and historical actions without a request principal. */
@Service
public class PipelineRolloutExecutionGuard {
    private final PipelineRolloutRevisionMapper revisions;
    private final PipelineAssetVersionMapper versions;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks;
    private final io.github.opensabre.iqc.schedule.dao.PeriodicScheduleRevisionMapper periodicVersions;
    private final io.github.opensabre.iqc.schedule.dao.EventSubscriptionRevisionMapper subscriptionVersions;
    @org.springframework.beans.factory.annotation.Autowired
    public PipelineRolloutExecutionGuard(PipelineRolloutRevisionMapper revisions,PipelineAssetVersionMapper versions,ObjectMapper json,
            io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks,io.github.opensabre.iqc.schedule.dao.PeriodicScheduleRevisionMapper periodicVersions,
            io.github.opensabre.iqc.schedule.dao.EventSubscriptionRevisionMapper subscriptionVersions){
        this.revisions=revisions;this.versions=versions;this.json=json;this.tasks=tasks;this.periodicVersions=periodicVersions;this.subscriptionVersions=subscriptionVersions;
    }
    public PipelineRolloutExecutionGuard(PipelineRolloutRevisionMapper revisions,PipelineAssetVersionMapper versions,ObjectMapper json,
            io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks,io.github.opensabre.iqc.schedule.dao.PeriodicScheduleRevisionMapper periodicVersions){
        this(revisions,versions,json,tasks,periodicVersions,null);
    }
    public PipelineRolloutExecutionGuard(PipelineRolloutRevisionMapper revisions,PipelineAssetVersionMapper versions,ObjectMapper json){
        this(revisions,versions,json,null,null);
    }

    public void validate(JsonNode snapshot,String expectedTaskId){
        try{
            if(snapshot==null||!snapshot.isObject()||snapshot.has("evaluationSnapshot")||snapshot.has("schemeSnapshot")
                    ||!"PIPELINE".equals(snapshot.path("executionStrategy").path("mode").asText()))throw new IllegalArgumentException();
            if(!snapshot.has("rolloutSnapshot")&&expectedTaskId!=null&&expectedTaskId.matches("t[a-f0-9]{63}")){
                if(tasks==null)throw new IllegalArgumentException();var task=tasks.selectById(expectedTaskId);
                if(task==null)throw new IllegalArgumentException();
                if(io.github.opensabre.iqc.schedule.TaskTriggerService.readAcceptedEvidence(task,json).rollout()==null)return;
            }
            var evidence=PipelineRolloutTaskEvidence.read(snapshot.path("rolloutSnapshot"),json);
            evidence.verifyFrozen(expectedTaskId==null?evidence.taskId():expectedTaskId,snapshot.path("pipelineSnapshot"),json);
            var marker=snapshot.path("pipelineSnapshot");
            if(!marker.path("trial").isBoolean()||marker.path("trial").booleanValue())throw new IllegalArgumentException();
            var source=evidence.revision().snapshot();String pipelineId=source.pipelineId();
            var row=revisions.selectOne(Wrappers.<PipelineRolloutRevision>lambdaQuery()
                    .eq(PipelineRolloutRevision::getPipelineId,pipelineId).eq(PipelineRolloutRevision::getRevisionNo,evidence.revision().revisionNo()));
            if(row==null||!pipelineId.equals(row.getPipelineId())||!Objects.equals(row.getRevisionNo(),evidence.revision().revisionNo())
                    ||!Objects.equals(row.getId(),evidence.revision().id())||!Objects.equals(row.getContentHash(),evidence.revision().contentHash())
                    ||!Objects.equals(row.getContentHash(),PipelineAssetService.hash(row.getSnapshotJson())))throw new IllegalArgumentException();
            var policy=source.policy();
            var baseline=originalRelease(pipelineId,policy.baselineVersionNo(),source.baselineReleaseHash());
            var candidate=originalRelease(pipelineId,policy.candidateVersionNo(),source.candidateReleaseHash());
            var comparison=BenchmarkVariableComparator.compare(baseline,candidate,policy.variable(),json);
            if(!Objects.equals(comparison,evidence.decision().comparison()))throw new IllegalArgumentException();
            if(PipelineRolloutTaskEvidence.PERIODIC_SCHEMA.equals(evidence.schemaVersion()))validatePeriodic(snapshot,evidence);
            if(PipelineRolloutTaskEvidence.TRIGGER_SCHEMA.equals(evidence.schemaVersion()))validateTriggered(snapshot,evidence);


        }catch(Exception invalid){throw IqcException.invalidState("灰度任务原修订、发布来源或比较证据不一致");}
    }
    private void validateTriggered(JsonNode snapshot,PipelineRolloutTaskEvidence evidence) throws java.io.IOException {
        if(tasks==null)throw new IllegalArgumentException();var task=tasks.selectById(evidence.taskId());
        if(task==null||!evidence.taskId().equals(task.getId())||!"PIPELINE".equals(task.getTaskType()))throw new IllegalArgumentException();
        var exact=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        var accepted=io.github.opensabre.iqc.schedule.TaskTriggerService.readAcceptedEvidence(task,exact);
        if(!Objects.equals(task.getName(),accepted.request().name().trim())
                ||!Objects.equals(task.getConcurrencyLimit(),accepted.request().concurrency()))throw new IllegalArgumentException();
        if(accepted.subscriptionId()!=null){
            if(subscriptionVersions==null)throw new IllegalArgumentException();
            var parent=subscriptionVersions.selectById(accepted.subscriptionRevisionId());
            io.github.opensabre.iqc.schedule.EventSubscriptionService.validateReceiptParent(accepted,parent,exact);
        }
        var trigger=exact.treeToValue(snapshot.path("triggerSnapshot"),io.github.opensabre.iqc.task.InspectionTaskService.TriggerIdentity.class);
        if(accepted.rollout()==null||trigger==null||trigger.evidence()==null
                ||!accepted.payloadHash().equals(trigger.payloadHash())||!accepted.request().occurredAt().equals(trigger.occurredAt())
                ||!"iqc-trigger-materialization-v1".equals(trigger.evidence().path("schemaVersion").asText())
                ||!accepted.request().eventType().equals(trigger.evidence().path("eventType").asText())
                ||!io.github.opensabre.iqc.selector.SelectorAssetService.hash(exact.writeValueAsString(accepted.rollout())).equals(
                        io.github.opensabre.iqc.selector.SelectorAssetService.hash(exact.writeValueAsString(evidence))))throw new IllegalArgumentException();
        evidence.verifyTriggered(accepted.ownerBound().userId(),accepted.request(),accepted.payloadHash(),snapshot.path("pipelineSnapshot"),exact);
        if(accepted.selector()!=null)io.github.opensabre.iqc.task.InspectionTaskService.validateSelectorEvidence(task,accepted.payloadHash(),accepted.selector(),exact);
        else if(task.getSelectionFilterJson()!=null||snapshot.path("pipelineSnapshot").has("selectorRequestHash"))throw new IllegalArgumentException();
        String revision=trigger.evidence().path("inputRevision").asText();
        if(!revision.matches("[a-f0-9]{64}")||!trigger.evidence().path("conversationId").isTextual()
                ||!trigger.evidence().path("filteredOut").isBoolean())throw new IllegalArgumentException();
        if(accepted.request().target()!=null&&(!accepted.request().target().inputRevision().equals(revision)
                ||accepted.request().target().conversationId()!=null&&!accepted.request().target().conversationId().equals(trigger.evidence().path("conversationId").asText())))
            throw new IllegalArgumentException();
        var inputs=snapshot.path("productionInputs").path("conversations");
        if(!inputs.isArray()||inputs.size()>1||trigger.evidence().path("filteredOut").booleanValue()!=inputs.isEmpty())throw new IllegalArgumentException();
        for(var input:inputs)if(!revision.equals(input.path("sourceFingerprint").asText())
                ||!trigger.evidence().path("conversationId").asText().equals(input.path("conversationId").asText()))throw new IllegalArgumentException();
    }
    private void validatePeriodic(JsonNode snapshot,PipelineRolloutTaskEvidence evidence) throws java.io.IOException {
        if(tasks==null||periodicVersions==null)throw new IllegalArgumentException();
        var exact=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        var identity=exact.treeToValue(snapshot.path("periodicSnapshot"),io.github.opensabre.iqc.task.InspectionTaskService.PeriodicIdentity.class);
        var task=tasks.selectById(evidence.taskId());
        if(identity==null||task==null||!evidence.taskId().equals(task.getId())||identity.plannedAt()==null
                ||!Objects.equals(task.getPeriodicScheduleId(),identity.scheduleId())||!Objects.equals(task.getPeriodicRevisionId(),identity.revisionId())
                ||!Objects.equals(task.getPlannedAtUtc(),java.time.LocalDateTime.ofInstant(identity.plannedAt(),java.time.ZoneOffset.UTC)))
            throw new IllegalArgumentException();
        var version=periodicVersions.selectById(identity.revisionId());
        if(version==null||!Objects.equals(version.getId(),identity.revisionId())||!Objects.equals(version.getScheduleId(),identity.scheduleId())
                ||version.getSnapshotJson()==null||version.getSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024
                ||!Objects.equals(version.getContentHash(),io.github.opensabre.iqc.selector.SelectorAssetService.hash(version.getSnapshotJson()))
                ||identity.evidence()==null||!"iqc-periodic-trigger-v1".equals(identity.evidence().path("schemaVersion").asText())
                ||!Objects.equals(version.getContentHash(),identity.evidence().path("revisionHash").asText()))throw new IllegalArgumentException();
        var published=exact.readValue(version.getSnapshotJson(),io.github.opensabre.iqc.schedule.PeriodicScheduleService.Published.class);
        if(!"iqc-periodic-rollout-release-v1".equals(published.schemaVersion())||published.definition()==null||!published.definition().usesRollout()
                ||published.pipeline()!=null||published.selector()==null||published.ownerBound()==null
                ||!"iqc-schedule-owner-scope-v1".equals(published.ownerBound().schemaVersion())
                ||published.ownerBound().owner()==null||published.ownerBound().owner().isBlank()
                ||!published.definition().targetPipelineId().equals(evidence.revision().snapshot().pipelineId())
                ||!published.definition().selector().selectorId().equals(published.selector().selectorId())
                ||published.definition().selector().versionNo()!=published.selector().versionNo()
                ||!Objects.equals(published.selector().contentHash(),io.github.opensabre.iqc.selector.SelectorAssetService.hash(exact.writeValueAsString(published.selector().definition()))))
            throw new IllegalArgumentException();
        String fingerprint=PipelineRolloutTaskEvidence.periodicRequestFingerprint(task.getName(),published.definition().selector(),
                task.getConcurrencyLimit(),identity,exact);
        if(!fingerprint.equals(evidence.requestFingerprint())||!Objects.equals(task.getCreatedBy(),published.ownerBound().owner()))throw new IllegalArgumentException();
        evidence.verify(published.ownerBound().userId(),published.definition().targetPipelineId(),
                PipelineRolloutTaskEvidence.periodicRequestKey(identity,exact),fingerprint,snapshot.path("pipelineSnapshot"),exact);
        io.github.opensabre.iqc.task.InspectionTaskService.validateSelectorEvidence(task,fingerprint,published.selector(),exact);
    }
    private PipelineRelease originalRelease(String pipelineId,int versionNo,String hash) throws java.io.IOException {
        var version=versions.selectOne(Wrappers.<PipelineAssetVersion>lambdaQuery()
                .eq(PipelineAssetVersion::getPipelineId,pipelineId).eq(PipelineAssetVersion::getVersionNo,versionNo));
        if(version==null||!pipelineId.equals(version.getPipelineId())||!Objects.equals(version.getVersionNo(),versionNo)
                ||!Objects.equals(hash,version.getContentHash())||!Objects.equals(hash,PipelineAssetService.hash(version.getSnapshotJson())))
            throw new IllegalArgumentException();
        PipelineAssetService.validatePublicationEvidence(version,json);
        return json.readerFor(PipelineRelease.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(version.getSnapshotJson());
    }
}
