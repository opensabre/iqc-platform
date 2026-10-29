package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.task.model.InspectionTask;

/** Reads only the persisted task/release; never resolves today's catalog for historical display. */
public final class FindingLabelTaskDirectory {
    private FindingLabelTaskDirectory(){ }
    public static JsonNode expected(InspectionTask task,ObjectMapper json) {
        if(task==null || task.getRuleSnapshotJson()==null || !task.getRuleSnapshotJson().contains("\"pipelineSnapshot\""))return null;
        try {
            var snapshot=PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());
            var marker=snapshot==null?null:snapshot.get("pipelineSnapshot");
            var body=marker==null?null:marker.get("release");
            if(body==null || !body.hasNonNull("labelPolicy"))return null;
            var release=json.treeToValue(body,PipelineRelease.class);
            release.labelPolicy().validateFrozen();
            if(!PipelineAssetService.hash(json.writeValueAsString(release)).equals(marker.path("contentHash").asText()))
                throw IqcException.invalidState("流水线标签目录的发布摘要不一致");
            var directory=release.labelPolicy().directory();
            // Tasks created before this field was mirrored can still use the verified release directory.
            if(task.getLabelScopeSnapshotJson()!=null && !task.getLabelScopeSnapshotJson().isBlank()
                    && !directory.equals(PipelineAssetService.readFrozenValue(json,task.getLabelScopeSnapshotJson(),JsonNode.class,release)))
                throw IqcException.invalidState("任务标签范围与流水线冻结目录不一致");
            return directory;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw IqcException.invalidState("流水线标签目录冻结协议损坏");
        }
    }
}
