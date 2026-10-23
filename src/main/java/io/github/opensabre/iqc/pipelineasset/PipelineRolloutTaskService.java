package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One original asset lock serializes policy changes and first request materialization across nodes. */
@Service
public class PipelineRolloutTaskService {
    private final PipelineAssetService pipelines;
    private final PipelineRolloutService rollouts;
    private final InspectionTaskService tasks;
    private final ObjectMapper json;
    private final IqcDataScope scope;
    private final io.github.opensabre.iqc.selector.SelectorAssetService selectorAssets;
    private final io.github.opensabre.iqc.selector.ConversationSelectionService selections;
    @org.springframework.beans.factory.annotation.Autowired
    public PipelineRolloutTaskService(PipelineAssetService pipelines,PipelineRolloutService rollouts,InspectionTaskService tasks,
            ObjectMapper json,IqcDataScope scope,io.github.opensabre.iqc.selector.SelectorAssetService selectorAssets,
            io.github.opensabre.iqc.selector.ConversationSelectionService selections){
        this.pipelines=pipelines;this.rollouts=rollouts;this.tasks=tasks;this.json=json;this.scope=scope;
        this.selectorAssets=selectorAssets;this.selections=selections;
    }
    public PipelineRolloutTaskService(PipelineAssetService pipelines,PipelineRolloutService rollouts,InspectionTaskService tasks,ObjectMapper json,IqcDataScope scope){
        this(pipelines,rollouts,tasks,json,scope,null,null);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public InspectionTask create(String id,String name,List<String> conversationIds,Integer concurrency,String requestKey){
        String actorId=scope.actorUserId();
        String taskId=PipelineRolloutTaskEvidence.taskId(actorId,id,requestKey,json);
        String fingerprint=PipelineRolloutTaskEvidence.requestFingerprint(name,conversationIds,concurrency,json);
        pipelines.lockForRolloutRequest(id);
        var existing=tasks.findRolloutRequest(taskId);
        if(existing!=null)return replay(id,actorId,requestKey,fingerprint,existing);
        var allocation=allocate(id,actorId,requestKey,taskId,fingerprint,PipelineRolloutTaskEvidence.SCHEMA);
        return tasks.createUsingPipelineRollout(allocation.selected(),name,conversationIds,concurrency,requestKey,allocation.evidence());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public InspectionTask createWithSelector(String id,String name,io.github.opensabre.iqc.selector.SelectorAssetService.Reference selector,
            Integer concurrency,String requestKey){
        if(selectorAssets==null||selections==null)throw IqcException.invalidState("灰度Selector物化依赖不可用");
        String actorId=scope.actorUserId();String taskId=PipelineRolloutTaskEvidence.taskId(actorId,id,requestKey,json);
        String fingerprint=PipelineRolloutTaskEvidence.selectorRequestFingerprint(name,selector,concurrency,json);
        pipelines.lockForRolloutRequest(id);
        var existing=tasks.findRolloutRequest(taskId);
        if(existing!=null)return tasks.requireRolloutSelectorRetry(replay(id,actorId,requestKey,fingerprint,existing),fingerprint,selector);
        var allocation=allocate(id,actorId,requestKey,taskId,fingerprint,PipelineRolloutTaskEvidence.SELECTOR_SCHEMA);
        var frozen=selectorAssets.publishedForTask(selector.selectorId(),selector.versionNo());
        var selection=selections.materialize(frozen.definition());
        return tasks.createUsingPipelineRolloutSelector(allocation.selected(),name,frozen,selection,concurrency,requestKey,allocation.evidence());
    }
    /** Only schedule code may supply this identity; the surrounding schedule transaction owns the cursor. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public InspectionTask createPeriodicWithSelector(String id,String name,io.github.opensabre.iqc.selector.SelectorAssetService.Prepared selector,
            InspectionTaskService.PeriodicIdentity identity,int concurrency){
        if(selectorAssets==null||selections==null||selector==null)throw IqcException.invalidState("周期灰度依赖不可用");
        String actorId=scope.actorUserId();String requestKey=PipelineRolloutTaskEvidence.periodicRequestKey(identity,json);
        String taskId=PipelineRolloutTaskEvidence.taskId(actorId,id,requestKey,json);
        var reference=new io.github.opensabre.iqc.selector.SelectorAssetService.Reference(selector.selectorId(),selector.versionNo());
        String fingerprint=PipelineRolloutTaskEvidence.periodicRequestFingerprint(name,reference,concurrency,identity,json);
        pipelines.lockForRolloutRequest(id);var existing=tasks.findRolloutRequest(taskId);
        if(existing!=null){
            replay(id,actorId,requestKey,fingerprint,existing);
            if(!Objects.equals(existing.getPeriodicScheduleId(),identity.scheduleId())||!Objects.equals(existing.getPeriodicRevisionId(),identity.revisionId())
                    ||!Objects.equals(existing.getPlannedAtUtc(),java.time.LocalDateTime.ofInstant(identity.plannedAt(),java.time.ZoneOffset.UTC)))
                throw IqcException.invalidState("周期灰度实例来源不一致");
            return tasks.requireRolloutSelectorRetry(existing,fingerprint,reference);
        }
        var allocation=allocate(id,actorId,requestKey,taskId,fingerprint,PipelineRolloutTaskEvidence.PERIODIC_SCHEMA);
        var locked=selectorAssets.publishedForTask(selector.selectorId(),selector.versionNo());
        if(!io.github.opensabre.iqc.selector.SelectorAssetService.hash(json.valueToTree(locked).toString()).equals(
                io.github.opensabre.iqc.selector.SelectorAssetService.hash(json.valueToTree(selector).toString())))
            throw IqcException.invalidState("周期Selector冻结来源已变化");
        var selection=selections.materializeAt(locked.definition(),identity.plannedAt());
        return tasks.createPeriodicPipelineRollout(allocation.selected(),name,locked,selection,identity,concurrency,requestKey,allocation.evidence());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void validateCurrentTarget(String id){currentSources(id);}
    private record Sources(PipelineRolloutService.Revision revision,PipelineAssetService.RolloutSources sources) { }
    private Sources currentSources(String id){
        var revision=rollouts.currentForTask(id);var sources=pipelines.rolloutSourcesForTask(id,revision.snapshot().policy());
        if(!Objects.equals(revision.snapshot().baselineReleaseHash(),sources.baseline().contentHash())
                ||!Objects.equals(revision.snapshot().candidateReleaseHash(),sources.candidate().contentHash()))
            throw IqcException.invalidState("灰度策略发布来源摘要已变化");
        return new Sources(revision,sources);
    }
    private record Allocation(PipelineAssetService.Prepared selected,PipelineRolloutTaskEvidence evidence) { }
    private Allocation allocate(String id,String actorId,String requestKey,String taskId,String fingerprint,String schema){
        var current=currentSources(id);var revision=current.revision();var sources=current.sources();var policy=revision.snapshot().policy();
        var decision=policy.preview(actorId,requestKey,sources.baseline(),sources.candidate(),json);
        var evidence=new PipelineRolloutTaskEvidence(schema,taskId,fingerprint,revision,decision);
        return new Allocation(decision.side()==PipelineRolloutPolicy.Side.CANDIDATE?sources.candidate():sources.baseline(),evidence);
    }
    private InspectionTask replay(String id,String actorId,String requestKey,String fingerprint,InspectionTask task){
        try{
            if(!"PIPELINE".equals(task.getTaskType()))throw new IllegalArgumentException();
            var exact=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
            var root=exact.readTree(task.getRuleSnapshotJson());
            var evidence=PipelineRolloutTaskEvidence.read(root.path("rolloutSnapshot"),exact);
            if(!Objects.equals(task.getId(),evidence.taskId()))throw new IllegalArgumentException();
            evidence.verify(actorId,id,requestKey,fingerprint,root.path("pipelineSnapshot"),exact);
            var original=rollouts.revisionForTask(id,evidence.revision().revisionNo());
            if(!Objects.equals(original.id(),evidence.revision().id())||!Objects.equals(original.contentHash(),evidence.revision().contentHash()))
                throw new IllegalArgumentException();
            return task;
        }catch(Exception invalid){throw IqcException.invalidState("原灰度任务请求内容不同或冻结证据无效");}
    }
}
