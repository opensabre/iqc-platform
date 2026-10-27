package io.github.opensabre.iqc.decision;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.decision.dao.DecisionRevisionMapper;
import io.github.opensabre.iqc.decision.model.DecisionRevision;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelinebridge.*;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DecisionRevisionService {
    public record Projection(String schemaVersion, String sourceResultId, String sourceHash,
            InspectionScoring.Result original, List<FindingFusion.Result> fusion,
            List<InspectionScoring.Decision> decisions, InspectionScoring.Result recalculated,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String originalRiskLevel,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String recalculatedRiskLevel,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            RiskPolicy.Decision originalRiskDecision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            RiskPolicy.Decision recalculatedRiskDecision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ReviewPolicy.Decision originalReviewDecision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ReviewPolicy.Decision recalculatedReviewDecision) {
        public Projection(String schemaVersion,String sourceResultId,String sourceHash,InspectionScoring.Result original,
                List<FindingFusion.Result> fusion,List<InspectionScoring.Decision> decisions,InspectionScoring.Result recalculated,
                String originalRiskLevel,String recalculatedRiskLevel,RiskPolicy.Decision originalRiskDecision,RiskPolicy.Decision recalculatedRiskDecision) {
            this(schemaVersion,sourceResultId,sourceHash,original,fusion,decisions,recalculated,originalRiskLevel,recalculatedRiskLevel,originalRiskDecision,recalculatedRiskDecision,null,null);
        }
        public Projection(String schemaVersion,String sourceResultId,String sourceHash,InspectionScoring.Result original,
                List<FindingFusion.Result> fusion,List<InspectionScoring.Decision> decisions,InspectionScoring.Result recalculated) {
            this(schemaVersion,sourceResultId,sourceHash,original,fusion,decisions,recalculated,null,null,null,null);
        }
    }
    private final DecisionRevisionMapper revisions;
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final PipelineAssetService assets;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.conversation.ProductionInputSnapshotService inputs;

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public DecisionRevision create(String resultId, String targetPipelineId, int targetVersion,
                                   String requestId, String reason) {
        if (targetPipelineId == null || targetPipelineId.isBlank() || targetPipelineId.length() > 64 || targetVersion < 1
                || requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")
                || reason == null || reason.isBlank() || reason.trim().length() > 1000)
            throw IqcException.invalidArgument("重算需要稳定请求标识和不超过1000字的原因");
        String actor = scope.owner();
        if (actor == null || actor.isBlank()) throw IqcException.accessDenied("重算必须登录");
        var initial = results.selectById(resultId);
        if (initial == null) throw IqcException.notFound("原始结果不存在");
        authorize(tasks.selectById(initial.getTaskId()));
        var task = tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery()
                .eq(InspectionTask::getId, initial.getTaskId()).last("FOR UPDATE"));
        authorize(task);
        String id = "dr-" + hash(List.of(actor, requestId)).substring(0,60);
        String fingerprint = hash(List.of(resultId, targetPipelineId, targetVersion, reason.trim()));
        var existing = revisions.selectById(id);
        if (existing != null) {
            if (!fingerprint.equals(existing.getRequestFingerprint()))
                throw IqcException.invalidArgument("同一重算请求标识不能用于不同内容");
            return existing;
        }
        if (!Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED").contains(task.getStatus()))
            throw IqcException.invalidState("任务结束后才能重算政策");
        var result = results.selectById(resultId);
        if (result == null || !task.getId().equals(result.getTaskId()))
            throw IqcException.invalidState("源结果已变化");
        try {
            var snapshot = io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());
            if (snapshot == null || !snapshot.isObject() || !snapshot.path("pipelineSnapshot").isObject())
                throw IqcException.invalidArgument("当前重算仅支持流水线事实结果");
            var marker = snapshot.path("pipelineSnapshot");
            var release = json.treeToValue(marker.path("release"), PipelineRelease.class);
            if (!hash(release).equals(marker.path("contentHash").asText()))
                throw IqcException.invalidState("源流水线快照摘要不一致");
            var trace = io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenValue(json,result.getExecutionTraceJson(), PipelineConversationEvaluator.Evaluation.class,release);
            if (trace == null || trace.fusion() == null || trace.scoring() == null)
                throw IqcException.invalidState("源执行轨迹不完整");
            if (!"iqc-pipeline-execution-v1".equals(trace.schemaVersion()))
                throw IqcException.invalidArgument("当前重算仅支持流水线事实结果");
            var raw = new ArrayList<Finding>();
            trace.fusion().forEach(value -> raw.addAll(value.rawFindings()));
            var originalFusion = FindingFusion.reproduce(raw,release.fusion(),trace.fusion());
            var preparedInput=inputs.prepare(task,snapshot);
            com.fasterxml.jackson.databind.JsonNode businessContext=null;
            if(preparedInput!=null) {
                inputs.validateResult(task,snapshot,preparedInput.messages().get(result.getConversationId()),trace,preparedInput);
                businessContext=preparedInput.businessContext(result.getConversationId());
            }
            var originalScore = PipelineDecisionEvaluator.evaluate(release,originalFusion,businessContext);
            if (!json.valueToTree(originalFusion).equals(json.valueToTree(trace.fusion()))
                    || !json.valueToTree(originalScore).equals(json.valueToTree(trace.scoring()))
                    || !json.valueToTree(originalScore).equals(json.valueToTree(json.readerFor(InspectionScoring.Result.class).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(result.getScoringResultJson())))
                    || !originalScore.scoreStatus().name().equals(result.getScoreStatus())
                    || (originalScore.finalScore() == null ? result.getFinalScore() != null
                    : result.getFinalScore() == null || originalScore.finalScore().compareTo(result.getFinalScore()) != 0))
                throw IqcException.invalidState("源事实或评分快照不一致");
            var originalRiskDecision=PipelineDecisionEvaluator.riskDecision(release,originalFusion,originalScore,businessContext);
            String originalRisk=PipelineDecisionEvaluator.riskLevel(release,originalFusion,originalScore,businessContext);
            if(!Objects.equals(originalRisk,trace.riskLevel()) || !Objects.equals(originalRisk,result.getRiskLevel())
                    || !json.valueToTree(originalRiskDecision).equals(json.valueToTree(trace.riskDecision())))
                throw IqcException.invalidState("源风险轨迹与冻结政策不一致");
            String samplingInputHash=preparedInput==null?null:PipelineConversationEvaluator.samplingInputHash(preparedInput.messages().get(result.getConversationId()),businessContext,json);
            String resultStatus=PipelineDecisionEvaluator.resultStatus(trace.run(),trace.items(),originalScore);
            var originalReview=PipelineDecisionEvaluator.reviewDecision(release,originalFusion,originalScore,originalRisk,resultStatus,trace.inputHash(),businessContext,samplingInputHash,json);
            if(!json.valueToTree(originalReview).equals(json.valueToTree(trace.reviewDecision())))throw IqcException.invalidState("源复核决定与冻结输入/政策不一致");
            var target = assets.publishedRelease(targetPipelineId,targetVersion);
            PipelineAssetService.validateNewRiskEvidence(target.release(),json);
            List<Finding> policyInputs=raw;
            if(target.release().fusion().method()==io.github.opensabre.iqc.fusion.FusionPolicy.Method.WEIGHTED
                    ||target.release().fusion().method()==io.github.opensabre.iqc.fusion.FusionPolicy.Method.CUSTOM){
                var withMissing=new ArrayList<>(raw);
                for(var node:target.release().pipeline().nodes()){
                    var definition=json.treeToValue(node.config().path("findingDefinition"),io.github.opensabre.iqc.finding.FindingDefinition.class);
                    if(raw.stream().anyMatch(finding->node.nodeId().equals(finding.source().nodeId())&&definition.code().equals(finding.code())))continue;
                    String identity=hash(Arrays.asList(target.release().fusion().method()==io.github.opensabre.iqc.fusion.FusionPolicy.Method.WEIGHTED?"weighted-policy-unavailable-v1":"custom-policy-unavailable-v1",result.getId(),target.pipelineId(),target.versionNo(),node.nodeId(),definition.code()));
                    withMissing.add(new Finding(identity,definition.code(),definition.category(),definition.valueType(),null,
                            Finding.State.NOT_EVALUATED,null,definition.defaultSeverity(),
                            "Target "+target.release().fusion().method().name().toLowerCase(java.util.Locale.ROOT)+" policy source was not observed; detectors were not rerun",List.of(),
                            new Finding.Source(node.nodeId(),node.type(),node.version(),1,null)));
                }
                policyInputs=withMissing;
            }
            var fusion = FindingFusion.fuse(policyInputs,target.release().fusion());
            var decisions = PipelineDecisionEvaluator.decisions(target.release().items(),fusion);
            var score = PipelineDecisionEvaluator.evaluate(target.release(),fusion,businessContext);
            PipelineDecisionEvaluator.requireRiskEvidence(target.release(),fusion,decisions,score,businessContext,true);
            String sourceHash = hash(Arrays.asList(task.getRuleSnapshotJson(),result.getId(),
                    result.getExecutionTraceJson(),result.getBusinessItemResultsJson(),result.getScoringResultJson()));
            var projection = new Projection("iqc-decision-revision-v1",resultId,sourceHash,originalScore,fusion,decisions,score,
                    originalRisk,PipelineDecisionEvaluator.riskLevel(target.release(),fusion,score,businessContext),
                    originalRiskDecision,PipelineDecisionEvaluator.riskDecision(target.release(),fusion,score,businessContext),originalReview,
                    PipelineDecisionEvaluator.reviewDecision(target.release(),fusion,score,PipelineDecisionEvaluator.riskLevel(target.release(),fusion,score,businessContext),
                            trace.run().status()==io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.FAILED||trace.run().status()==io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.CANCELLED||decisions.stream().anyMatch(d->d.status()==InspectionScoring.ItemStatus.ERROR)?"ERROR":score.conclusion().name(),trace.inputHash(),businessContext,samplingInputHash,json));
            var saved = new DecisionRevision();
            saved.setId(id); saved.setSourceResultId(resultId); saved.setTaskId(task.getId());
            saved.setSourceHash(sourceHash); saved.setRequestFingerprint(fingerprint);
            saved.setTargetPolicyJson(json.writeValueAsString(target));
            saved.setProjectionJson(json.writeValueAsString(projection)); saved.setReason(reason.trim());
            saved.setCreatedBy(actor); saved.setOwnerGroupId(task.getOwnerGroupId());
            try { revisions.insert(saved); }
            catch (org.springframework.dao.DuplicateKeyException concurrent) {
                var winner = revisions.selectOne(Wrappers.<DecisionRevision>lambdaQuery()
                        .eq(DecisionRevision::getId,id).last("FOR UPDATE"));
                if (winner == null || !fingerprint.equals(winner.getRequestFingerprint()))
                    throw IqcException.invalidArgument("同一重算请求标识不能用于不同内容");
                return winner;
            }
            return saved;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            throw IqcException.invalidState("重算事实或政策快照无效");
        }
    }

    @Transactional(readOnly=true)
    public List<DecisionRevision> history(String resultId) {
        var result = results.selectById(resultId);
        if (result == null) throw IqcException.notFound("原始结果不存在");
        authorize(tasks.selectById(result.getTaskId()));
        return revisions.selectList(Wrappers.<DecisionRevision>lambdaQuery()
                .eq(DecisionRevision::getSourceResultId,resultId).orderByDesc(DecisionRevision::getCreatedTime)
                .orderByDesc(DecisionRevision::getId).last("LIMIT 100"));
    }

    private void authorize(InspectionTask task) {
        if (task == null || !scope.canView(task.getCreatedBy(),task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权访问重算源结果");
    }
    private String hash(Object value) {
        try { return PipelineAssetService.hash(json.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw IqcException.invalidState("重算快照无法序列化"); }
    }
}
