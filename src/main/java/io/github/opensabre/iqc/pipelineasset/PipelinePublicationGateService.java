package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** All measurements come from validated immutable evaluation artifacts, never client-reported scores. */
@Service @RequiredArgsConstructor
public class PipelinePublicationGateService {
    private final PipelineEvaluationService evaluations;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.task.dao.TaskExecutionMapper executions;
    public record ExecutionEvidence(String taskId,String executionId) { }
    public enum Status { PASSED,FAILED,NOT_APPLICABLE }
    public record Criterion(String code,String purpose,Status status,BigDecimal actual,BigDecimal threshold,String reason) { }
    public record ReportEvidence(String purpose,String taskId,String executionId,String datasetId,int datasetVersion,
                                 String datasetHash,String reportHash,PipelineEvaluationService.Report report) { }
    public record Decision(String schemaVersion,String pipelineId,int draftRevision,String releaseHash,
                           PublicationGatePolicy policy,boolean passed,List<Criterion> criteria,List<ReportEvidence> evidence) {
        public Decision {criteria=List.copyOf(criteria);evidence=List.copyOf(evidence);}
    }
    public Decision evaluate(String pipelineId,int revision,String releaseHash,PipelineRelease release,
                             ExecutionEvidence quality,ExecutionEvidence regression) {
        var qualityContext=context(quality,pipelineId,revision,releaseHash);
        var regressionContext=context(regression,pipelineId,revision,releaseHash);
        if(regressionContext.prepared().dataset().type()!=DatasetDraft.Type.REGRESSION)
            throw IqcException.invalidArgument("回归准入必须使用REGRESSION冻结数据集");
        var q=evaluations.report(quality.taskId(),quality.executionId());
        var r=evaluations.report(regression.taskId(),regression.executionId());
        // A force publication can override failed measured criteria, never missing or fabricated execution evidence.
        if(q.availableSamples()==0 || r.availableSamples()==0) throw IqcException.invalidState("发布必须提供双层真实评测产物");
        var policy=release.publicationGate()==null?PublicationGatePolicy.defaults():release.publicationGate();
        var criteria=new ArrayList<Criterion>();
        addCoverage(criteria,"QUALITY",q,policy); addCoverage(criteria,"REGRESSION",r,policy);
        minimum(criteria,"HIGH_RISK_RECALL","QUALITY",q.businessMetrics().highRiskRecall(),policy.minimumRiskRecall());
        minimum(criteria,"OVERALL_F1","QUALITY",q.findingMetrics().aggregate().f1(),policy.minimumOverallF1());
        minimum(criteria,"REGRESSION_PASS","REGRESSION",r.regressionMetrics().passRate(),policy.minimumRegressionPass());
        addStructured(criteria,"QUALITY",q,policy); addStructured(criteria,"REGRESSION",r,policy);
        maximum(criteria,"P95_EVALUATOR_MS","QUALITY",decimal(q.latencyMetrics().p95Ms()),BigDecimal.valueOf(policy.maximumP95Ms()));
        maximum(criteria,"P95_EVALUATOR_MS","REGRESSION",decimal(r.latencyMetrics().p95Ms()),BigDecimal.valueOf(policy.maximumP95Ms()));
        var evidence=List.of(evidence("QUALITY",quality,qualityContext,q),evidence("REGRESSION",regression,regressionContext,r));
        return new Decision("iqc-publication-gate-decision-v1",pipelineId,revision,releaseHash,policy,
                criteria.stream().noneMatch(c->c.status()==Status.FAILED),criteria,evidence);
    }
    private PipelineEvaluationService.Context context(ExecutionEvidence source,String pipelineId,int revision,String digest) {
        if(source==null || source.taskId()==null || source.taskId().isBlank() || source.executionId()==null || source.executionId().isBlank())
            throw IqcException.invalidArgument("发布需要精确质量与回归Task/EID");
        var context=evaluations.context(source.taskId()); var target=context.snapshot().path("pipelineSnapshot");
        var execution=executions.selectById(source.executionId());
        if(execution==null || !source.taskId().equals(execution.getTaskId())
                || !Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED").contains(execution.getStatus()))
            throw IqcException.invalidState("发布证据必须是该评测任务的终态执行轮次");
        if(!pipelineId.equals(target.path("pipelineId").asText()) || !digest.equals(target.path("contentHash").asText()))
            throw IqcException.invalidState("评测目标不属于当前流水线冻结内容");
        boolean published=target.path("versionNo").isIntegralNumber() && target.path("versionNo").asInt()>0;
        if(!published && (target.path("draftRevision").asInt(-1)!=revision || !target.path("trial").asBoolean()))
            throw IqcException.invalidState("草稿评测必须绑定当前精确修订");
        return context;
    }
    private ReportEvidence evidence(String purpose,ExecutionEvidence source,PipelineEvaluationService.Context context,
                                    PipelineEvaluationService.Report report) {
        return new ReportEvidence(purpose,source.taskId(),report.executionId(),context.prepared().dataset().datasetId(),
                context.snapshot().path("evaluationSnapshot").path("versionNo").asInt(),report.datasetHash(),
                PipelineAssetService.hash(json.valueToTree(report).toString()),report);
    }
    private void addCoverage(List<Criterion> criteria,String purpose,PipelineEvaluationService.Report report,PublicationGatePolicy policy) {
        criteria.add(new Criterion("COMPLETE",purpose,report.complete()?Status.PASSED:Status.FAILED,
                BigDecimal.valueOf(report.availableSamples()),BigDecimal.valueOf(report.expectedSamples()),
                report.complete()?null:"执行非终态或仍有缺失样本"));
        minimum(criteria,"MINIMUM_GOLD_SAMPLES",purpose,BigDecimal.valueOf(report.expectedSamples()),BigDecimal.valueOf(policy.minimumSamples()));
        criteria.add(new Criterion("EXECUTION_SUCCESS",purpose,report.successfulSamples()==report.expectedSamples()?Status.PASSED:Status.FAILED,
                BigDecimal.valueOf(report.successfulSamples()),BigDecimal.valueOf(report.expectedSamples()),
                report.successfulSamples()==report.expectedSamples()?null:"缺失或失败产物不能视为成功"));
    }
    private void addStructured(List<Criterion> criteria,String purpose,PipelineEvaluationService.Report report,PublicationGatePolicy policy) {
        var measured=report.structuredMetrics();
        if(measured.declaredLlmNodes()==0) {
            criteria.add(new Criterion("STRUCTURED_SUCCESS",purpose,Status.NOT_APPLICABLE,null,policy.minimumStructuredSuccess(),"冻结Pipeline没有LLM节点"));
        } else minimum(criteria,"STRUCTURED_SUCCESS",purpose,measured.successRate(),policy.minimumStructuredSuccess());
    }
    private void minimum(List<Criterion> criteria,String code,String purpose,BigDecimal actual,BigDecimal threshold) {
        criteria.add(new Criterion(code,purpose,actual!=null && actual.compareTo(threshold)>=0?Status.PASSED:Status.FAILED,
                actual,threshold,actual==null?"缺少分母或尚未测量":actual.compareTo(threshold)<0?"低于最低阈值":null));
    }
    private void maximum(List<Criterion> criteria,String code,String purpose,BigDecimal actual,BigDecimal threshold) {
        criteria.add(new Criterion(code,purpose,actual!=null && actual.compareTo(threshold)<=0?Status.PASSED:Status.FAILED,
                actual,threshold,actual==null?"尚未测量":actual.compareTo(threshold)>0?"超过最大阈值":null));
    }
    private BigDecimal decimal(Long value){return value==null?null:BigDecimal.valueOf(value);}
}
