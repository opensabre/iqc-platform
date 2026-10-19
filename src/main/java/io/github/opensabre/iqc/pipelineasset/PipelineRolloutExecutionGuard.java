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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read-only original-source verification, usable by workers and historical actions without a request principal. */
@Service
@RequiredArgsConstructor
public class PipelineRolloutExecutionGuard {
    private final PipelineRolloutRevisionMapper revisions;
    private final PipelineAssetVersionMapper versions;
    private final ObjectMapper json;

    public void validate(JsonNode snapshot,String expectedTaskId){
        try{
            if(snapshot==null||!snapshot.isObject()||snapshot.has("evaluationSnapshot")||snapshot.has("schemeSnapshot")
                    ||!"PIPELINE".equals(snapshot.path("executionStrategy").path("mode").asText()))throw new IllegalArgumentException();
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
        }catch(Exception invalid){throw IqcException.invalidState("灰度任务原修订、发布来源或比较证据不一致");}
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
