package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.ProductionInputSnapshotService;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipeline.PipelineRuntime;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/** No side effects. Called inside the short enqueue transaction, never around external I/O. */
@Service
@RequiredArgsConstructor
public class ActionProductionSourceValidator {
    public record Reference(String taskId,String executionId,String conversationId,String resultId,String owner) { }
    public record Validated(Reference reference,String ownerGroupId,String decisionReference,
                            String inputSnapshotJson,String inputSnapshotHash,ActionPolicy policy,ActionPolicy.Context context,
                            io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound ownerBound,String taskStatus) {
        public Validated(Reference reference,String ownerGroupId,String decisionReference,String inputSnapshotJson,String inputSnapshotHash,
                         ActionPolicy policy,ActionPolicy.Context context,io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound ownerBound) {
            this(reference,ownerGroupId,decisionReference,inputSnapshotJson,inputSnapshotHash,policy,context,ownerBound,null);
        }
    }
    private final InspectionTaskMapper tasks;
    private final TaskExecutionMapper executions;
    private final ConversationInspectionResultMapper results;
    private final PipelineAssetService assets;
    private final ProductionInputSnapshotService inputs;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.MANDATORY)
    public Validated validate(Reference reference) {
        requireReference(reference);
        // Same task/execution lock order as task control and gateway tool exposure.
        var task=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery()
                .eq(InspectionTask::getId,reference.taskId()).last("FOR UPDATE"));
        if(task==null || !"PIPELINE".equals(task.getTaskType())
                || !Objects.equals(task.getCreatedBy(),reference.owner())
                || !Objects.equals(task.getCurrentExecutionId(),reference.executionId())
                || task.getStatus()==null || !Set.of("RUNNING","SUCCEEDED","PARTIAL_FAILED","FAILED").contains(task.getStatus())
                || Boolean.TRUE.equals(task.getPauseRequested()) || Boolean.TRUE.equals(task.getCancelRequested()))
            throw IqcException.invalidState("动作生产任务来源已失效");
        var execution=executions.selectOne(Wrappers.<TaskExecution>lambdaQuery()
                .eq(TaskExecution::getId,reference.executionId()).last("FOR UPDATE"));
        if(execution==null || !Objects.equals(execution.getTaskId(),task.getId())
                || execution.getStatus()==null || !Set.of("RUNNING","SUCCEEDED","PARTIAL_FAILED","FAILED").contains(execution.getStatus()))
            throw IqcException.invalidState("动作生产执行来源已失效");
        var result=results.selectOne(Wrappers.<ConversationInspectionResult>lambdaQuery()
                .eq(ConversationInspectionResult::getId,reference.resultId()).last("FOR UPDATE"));
        if(result==null || !Objects.equals(result.getTaskId(),reference.taskId())
                || !Objects.equals(result.getExecutionId(),reference.executionId())
                || !Objects.equals(result.getConversationId(),reference.conversationId())
                || !"PIPELINE".equals(result.getAggregationMode()))
            throw IqcException.invalidState("动作生产结果归属不一致");
        return validateFacts(reference,task,result);
    }

    /** Historical artifact proof: does not claim, lock, send, or require the task's current execution. */
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Validated validateHistorical(Reference reference) {
        requireReference(reference);
        var task=tasks.selectById(reference.taskId());
        var execution=executions.selectById(reference.executionId());
        var result=results.selectById(reference.resultId());
        if(task==null || !"PIPELINE".equals(task.getTaskType()) || !Objects.equals(task.getCreatedBy(),reference.owner())
                || execution==null || !Objects.equals(execution.getTaskId(),reference.taskId())
                || result==null || !Objects.equals(result.getTaskId(),reference.taskId())
                || !Objects.equals(result.getExecutionId(),reference.executionId())
                || !Objects.equals(result.getConversationId(),reference.conversationId())
                || !"PIPELINE".equals(result.getAggregationMode()))
            throw IqcException.invalidState("历史产物生产任务、执行或结果归属不一致");
        return validateFacts(reference,task,result);
    }

    private void requireReference(Reference reference) {
        if(reference==null)throw IqcException.invalidArgument("动作生产来源不能为空");
        for(var id:new String[]{reference.taskId(),reference.executionId(),reference.conversationId(),reference.resultId()})
            if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}"))
                throw IqcException.invalidArgument("动作生产来源标识无效");
        if(reference.owner()==null || reference.owner().isBlank() || reference.owner().length()>128
                || reference.owner().codePoints().anyMatch(Character::isISOControl))
            throw IqcException.invalidArgument("动作生产负责人无效");
    }

    private Validated validateFacts(Reference reference,InspectionTask task,ConversationInspectionResult result) {
        try {
            var snapshot=json.readTree(task.getRuleSnapshotJson());
            var release=assets.publishedTaskRelease(snapshot);
            io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound ownerBound=null;
            if(release.actionPolicy()!=null && !release.actionPolicy().actions().isEmpty()) {
                ownerBound=json.treeToValue(snapshot.path("actionOwnerBound"),io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound.class);
                if(ownerBound==null || !"iqc-schedule-owner-scope-v1".equals(ownerBound.schemaVersion())
                        || !reference.owner().equals(ownerBound.owner()) || ownerBound.userId()==null
                        || !ownerBound.userId().matches("[A-Za-z0-9:_-]{1,64}"))
                    throw IqcException.invalidState("动作来源缺少稳定负责人身份");
            }
            var prepared=inputs.prepare(task,snapshot);
            if(prepared==null)throw IqcException.invalidState("动作来源缺少冻结生产输入");
            var messages=prepared.messages().get(reference.conversationId());
            var trace=json.readValue(result.getExecutionTraceJson(),PipelineConversationEvaluator.Evaluation.class);
            if(trace==null || !"iqc-pipeline-execution-v1".equals(trace.schemaVersion())
                    || trace.run()==null || trace.run().status()==null || trace.fusion()==null || trace.items()==null || trace.scoring()==null)
                throw IqcException.invalidState("动作来源执行轨迹不完整");
            inputs.validateResult(task,snapshot,messages,trace,prepared);
            var raw=new ArrayList<Finding>();
            trace.fusion().forEach(fact->raw.addAll(fact.rawFindings()));
            var fused=FindingFusion.fuse(raw,release.fusion());
            var score=PipelineDecisionEvaluator.evaluate(release.scoring(),release.items(),fused);
            var decisions=new java.util.HashMap<String,InspectionScoring.ItemStatus>();
            PipelineDecisionEvaluator.decisions(release.items(),fused).forEach(value->decisions.put(value.itemCode(),value.status()));
            var seen=new java.util.HashSet<String>();
            for(var item:trace.items()) {
                if(item==null || !seen.add(item.itemCode()) || !Objects.equals(decisions.get(item.itemCode()),item.status()))
                    throw IqcException.invalidState("动作来源业务项状态不一致");
            }
            if(!seen.equals(decisions.keySet()))throw IqcException.invalidState("动作来源业务项清单不一致");
            String risk=PipelineDecisionEvaluator.riskLevel(release.items(),fused,score);
            boolean failed=trace.run().status()==PipelineRuntime.Status.FAILED
                    || trace.run().status()==PipelineRuntime.Status.CANCELLED
                    || trace.items().stream().anyMatch(item->item.status()==InspectionScoring.ItemStatus.ERROR);
            if(!json.valueToTree(fused).equals(json.valueToTree(trace.fusion()))
                    || !json.valueToTree(score).equals(json.valueToTree(trace.scoring()))
                    || !json.valueToTree(score).equals(json.readTree(result.getScoringResultJson()))
                    || !json.valueToTree(trace.items()).equals(json.readTree(result.getBusinessItemResultsJson()))
                    || !Objects.equals(score.scoreStatus().name(),result.getScoreStatus())
                    || !Objects.equals(failed?"ERROR":score.conclusion().name(),result.getResultStatus())
                    || !Objects.equals(risk,trace.riskLevel()) || !Objects.equals(risk,result.getRiskLevel())
                    || (score.finalScore()==null?result.getFinalScore()!=null:
                        result.getFinalScore()==null || score.finalScore().compareTo(result.getFinalScore())!=0))
                throw IqcException.invalidState("动作来源事实或决策投影不一致");
            var frozen=json.createObjectNode().put("schemaVersion","iqc-action-production-input-v1")
                    .put("taskId",reference.taskId()).put("executionId",reference.executionId())
                    .put("conversationId",reference.conversationId()).put("resultId",reference.resultId())
                    .put("owner",reference.owner()).put("ownerGroupId",task.getOwnerGroupId())
                    .put("taskSnapshotJson",task.getRuleSnapshotJson()).put("traceJson",result.getExecutionTraceJson())
                    .put("scoringResultJson",result.getScoringResultJson()).put("businessItemResultsJson",result.getBusinessItemResultsJson())
                    .put("resultStatus",result.getResultStatus()).put("riskLevel",risk);
            String body=json.writeValueAsString(frozen);
            var facts=new java.util.HashMap<String,FindingFusion.Result>();fused.forEach(fact->facts.put(fact.code(),fact));
            var context=new ActionPolicy.Context(facts,decisions,score.scoreStatus(),score.finalScore(),risk,result.getResultStatus());
            return new Validated(reference,task.getOwnerGroupId(),result.getId(),body,PipelineAssetService.hash(body),
                    release.actionPolicy(),context,ownerBound,task.getStatus());
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw IqcException.invalidState("动作生产来源快照无效");
        }
    }
}
