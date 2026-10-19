package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One original asset lock serializes policy changes and first request materialization across nodes. */
@Service
@RequiredArgsConstructor
public class PipelineRolloutTaskService {
    private final PipelineAssetService pipelines;
    private final PipelineRolloutService rollouts;
    private final InspectionTaskService tasks;
    private final ObjectMapper json;
    private final IqcDataScope scope;

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public InspectionTask create(String id,String name,List<String> conversationIds,Integer concurrency,String requestKey){
        String actorId=scope.actorUserId();
        String taskId=PipelineRolloutTaskEvidence.taskId(actorId,id,requestKey,json);
        String fingerprint=PipelineRolloutTaskEvidence.requestFingerprint(name,conversationIds,concurrency,json);
        pipelines.lockForRolloutRequest(id);
        var existing=tasks.findRolloutRequest(taskId);
        if(existing!=null)return replay(id,actorId,requestKey,fingerprint,existing);
        var revision=rollouts.currentForTask(id);
        var policy=revision.snapshot().policy();
        var sources=pipelines.rolloutSourcesForTask(id,policy);
        if(!Objects.equals(revision.snapshot().baselineReleaseHash(),sources.baseline().contentHash())
                ||!Objects.equals(revision.snapshot().candidateReleaseHash(),sources.candidate().contentHash()))
            throw IqcException.invalidState("灰度策略发布来源摘要已变化");
        var decision=policy.preview(actorId,requestKey,sources.baseline(),sources.candidate(),json);
        var evidence=new PipelineRolloutTaskEvidence(PipelineRolloutTaskEvidence.SCHEMA,taskId,fingerprint,revision,decision);
        var selected=decision.side()==PipelineRolloutPolicy.Side.CANDIDATE?sources.candidate():sources.baseline();
        return tasks.createUsingPipelineRollout(selected,name,conversationIds,concurrency,requestKey,evidence);
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
