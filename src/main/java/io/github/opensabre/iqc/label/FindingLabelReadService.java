package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;

/** Verifies historical artifacts inside the caller's authorized snapshot, without claiming actions or rewriting results. */
@Service @RequiredArgsConstructor
public class FindingLabelReadService {
    private final ActionProductionSourceValidator sources;
    private final ActionExecutionMapper actions;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public FindingLabelProjection.Projection read(InspectionTask task,ConversationInspectionResult result,InspectionLabelResult row) {
        LabelResultSourceValidator.validate(row,json);
        requireOwner(task,result,row);
        return readPrepared(task,result,row,prepare(task,result));
    }

    /** Request-local proof reuse. Each canonical production result is validated once for this batch. */
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Map<String,FindingLabelProjection.Projection> readBatch(Map<String,InspectionTask> tasks,
            Map<String,ConversationInspectionResult> results,List<InspectionLabelResult> rows) {
        var grouped=new java.util.LinkedHashMap<String,java.util.ArrayList<InspectionLabelResult>>();
        for(var row:rows) {
            LabelResultSourceValidator.validate(row,json);
            if(LabelResultSourceValidator.FINDING.equals(row.getGenerationSource()))
                grouped.computeIfAbsent(row.getConversationResultId(),ignored->new java.util.ArrayList<>()).add(row);
        }
        var projections=new HashMap<String,FindingLabelProjection.Projection>();
        for(var group:grouped.entrySet()) {
            var result=results.get(group.getKey());
            var task=result==null?null:tasks.get(result.getTaskId());
            for(var row:group.getValue())requireOwner(task,result,row);
            // Retain only one full production proof at a time; input snapshots may include a large task manifest.
            var proof=prepare(task,result);
            for(var row:group.getValue()) {
                if(row.getId()==null || projections.putIfAbsent(row.getId(),readPrepared(task,result,row,proof))!=null)
                    throw IqcException.invalidState("Finding标签结果身份缺失或重复");
            }
        }
        return Map.copyOf(projections);
    }

    private record Prepared(ActionProductionSourceValidator.Validated validated,PipelineRelease release,String policyBody) { }
    private void requireOwner(InspectionTask task,ConversationInspectionResult result,InspectionLabelResult row) {
        if(!LabelResultSourceValidator.FINDING.equals(row.getGenerationSource()) || task==null || result==null
                || !Objects.equals(task.getId(),result.getTaskId()) || !Objects.equals(result.getId(),row.getConversationResultId())
                || !Objects.equals(task.getCreatedBy(),row.getCreatedBy()))
            throw IqcException.invalidState("Finding标签不属于当前生产任务结果");
    }
    private Prepared prepare(InspectionTask task,ConversationInspectionResult result) {
        var validated=sources.validateHistorical(new ActionProductionSourceValidator.Reference(task.getId(),result.getExecutionId(),
                result.getConversationId(),result.getId(),task.getCreatedBy()));
        if(validated.policy()==null)throw IqcException.invalidState("Finding标签缺少冻结动作政策");
        try {
            if(!Objects.equals(task.getRuleSnapshotJson(),json.readTree(validated.inputSnapshotJson()).path("taskSnapshotJson").asText())
                    || !Objects.equals(task.getOwnerGroupId(),validated.ownerGroupId()))
                throw IqcException.invalidState("Finding标签读取快照与持久任务不一致");
            if(FindingLabelTaskDirectory.expected(task,json)==null)throw IqcException.invalidState("Finding标签缺少冻结目录");
            var release=json.treeToValue(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson()).path("pipelineSnapshot").path("release"),PipelineRelease.class);
            return new Prepared(validated,release,json.writeValueAsString(validated.policy()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw IqcException.invalidState("Finding标签生产政策损坏");
        }
    }
    private FindingLabelProjection.Projection readPrepared(InspectionTask task,ConversationInspectionResult result,
            InspectionLabelResult row,Prepared prepared) {
        var validated=prepared.validated();
        var origin=actions.selectById(row.getSourceActionExecutionId());
        if(origin==null || origin.getAttemptCount()==null || origin.getAttemptCount()<1 || origin.getClaimToken()==null
                || !origin.getClaimToken().matches("[a-f0-9]{32}") || origin.getClaimedAtUtc()==null)
            throw IqcException.invalidState("Finding标签缺少实际动作领取来源");
        try {
            var declared=validated.policy().actions().stream().filter(action->Objects.equals(action.actionId(),origin.getActionId())).findFirst().orElseThrow();
            if(declared.type()!=ActionExecutionContract.Type.UPDATE_LABEL || declared.effectiveAdapterVersion()!=1
                    || ActionPolicy.evaluate(declared.condition(),validated.context())!=ActionPolicy.Match.MATCH)
                throw IqcException.invalidState("Finding标签原始动作类型或条件不一致");
            String policyBody=prepared.policyBody();
            var source=new ActionExecutionContract.Source(task.getId(),result.getExecutionId(),result.getConversationId(),result.getId(),
                    validated.decisionReference(),SelectorAssetService.hash(policyBody),declared.actionId(),task.getCreatedBy());
            ActionEnqueueService.requireSame(origin,source,declared,validated,source.idempotencyKey(declared.type()),policyBody);
            if(!Objects.equals(row.getSourceInputHash(),validated.inputSnapshotHash()))
                throw IqcException.invalidState("Finding标签冻结生产输入摘要不一致");
            var release=prepared.release();
            var binding=FindingLabelActionConfiguration.requireBinding(release.labelPolicy(),declared.configuration());
            var expected=FindingLabelProjection.project(release.labelPolicy().projectionBinding(binding),validated.context().findings().get(binding.findingCode()));
            var persisted=FindingLabelProjection.read(json,row.getValueJson());
            String id="fl-"+SelectorAssetService.hash(json.writeValueAsString(List.of(result.getId(),binding.labelId(),binding.valueCode()))).substring(0,60);
            if(!Objects.equals(id,row.getId()) || !SelectorAssetService.hash(json.writeValueAsString(expected)).equals(row.getSourceProjectionHash()))
                throw IqcException.invalidState("Finding标签值或证据与实际生产融合结果不一致");
            return persisted;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | java.util.NoSuchElementException failure) {
            throw IqcException.invalidState("Finding标签持久来源或冻结投影损坏");
        }
    }
}
