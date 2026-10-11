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

/** Verifies historical artifacts inside the caller's authorized snapshot, without claiming actions or rewriting results. */
@Service @RequiredArgsConstructor
public class FindingLabelReadService {
    private final ActionProductionSourceValidator sources;
    private final ActionExecutionMapper actions;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public FindingLabelProjection.Projection read(InspectionTask task,ConversationInspectionResult result,InspectionLabelResult row) {
        LabelResultSourceValidator.validate(row,json);
        if(!LabelResultSourceValidator.FINDING.equals(row.getGenerationSource()) || task==null || result==null
                || !Objects.equals(task.getId(),result.getTaskId()) || !Objects.equals(result.getId(),row.getConversationResultId())
                || !Objects.equals(task.getCreatedBy(),row.getCreatedBy()))
            throw IqcException.invalidState("Finding标签不属于当前生产任务结果");
        var validated=sources.validateHistorical(new ActionProductionSourceValidator.Reference(task.getId(),result.getExecutionId(),
                result.getConversationId(),result.getId(),task.getCreatedBy()));
        if(validated.policy()==null)throw IqcException.invalidState("Finding标签缺少冻结动作政策");
        var origin=actions.selectById(row.getSourceActionExecutionId());
        if(origin==null || origin.getAttemptCount()==null || origin.getAttemptCount()<1 || origin.getClaimToken()==null
                || !origin.getClaimToken().matches("[a-f0-9]{32}") || origin.getClaimedAtUtc()==null)
            throw IqcException.invalidState("Finding标签缺少实际动作领取来源");
        try {
            if(!Objects.equals(task.getRuleSnapshotJson(),json.readTree(validated.inputSnapshotJson()).path("taskSnapshotJson").asText())
                    || !Objects.equals(task.getOwnerGroupId(),validated.ownerGroupId()))
                throw IqcException.invalidState("Finding标签读取快照与持久任务不一致");
            var declared=validated.policy().actions().stream().filter(action->Objects.equals(action.actionId(),origin.getActionId())).findFirst().orElseThrow();
            if(declared.type()!=ActionExecutionContract.Type.UPDATE_LABEL || declared.effectiveAdapterVersion()!=1
                    || ActionPolicy.evaluate(declared.condition(),validated.context())!=ActionPolicy.Match.MATCH)
                throw IqcException.invalidState("Finding标签原始动作类型或条件不一致");
            String policyBody=json.writeValueAsString(validated.policy());
            var source=new ActionExecutionContract.Source(task.getId(),result.getExecutionId(),result.getConversationId(),result.getId(),
                    validated.decisionReference(),SelectorAssetService.hash(policyBody),declared.actionId(),task.getCreatedBy());
            ActionEnqueueService.requireSame(origin,source,declared,validated,source.idempotencyKey(declared.type()),policyBody);
            if(!Objects.equals(row.getSourceInputHash(),validated.inputSnapshotHash()))
                throw IqcException.invalidState("Finding标签冻结生产输入摘要不一致");
            if(FindingLabelTaskDirectory.expected(task,json)==null)throw IqcException.invalidState("Finding标签缺少冻结目录");
            var release=json.treeToValue(json.readTree(task.getRuleSnapshotJson()).path("pipelineSnapshot").path("release"),PipelineRelease.class);
            var target=FindingLabelActionConfiguration.parse(declared.configuration());
            var binding=release.labelPolicy().bindings().stream().filter(value->value.labelId().equals(target.labelId())
                    && value.valueCode().equals(target.valueCode())).findFirst().orElseThrow();
            var expected=FindingLabelProjection.project(release.labelPolicy().projectionBinding(binding),validated.context().findings().get(binding.findingCode()));
            var persisted=json.readValue(row.getValueJson(),FindingLabelProjection.Projection.class);
            String id="fl-"+SelectorAssetService.hash(json.writeValueAsString(List.of(result.getId(),binding.labelId(),binding.valueCode()))).substring(0,60);
            if(!Objects.equals(id,row.getId()) || !json.valueToTree(expected).equals(json.valueToTree(persisted)))
                throw IqcException.invalidState("Finding标签值或证据与实际生产融合结果不一致");
            return persisted;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | java.util.NoSuchElementException failure) {
            throw IqcException.invalidState("Finding标签持久来源或冻结投影损坏");
        }
    }
}
