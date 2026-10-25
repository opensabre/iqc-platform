package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent experiments reuse two immutable evaluation tasks and their existing execution leases. */
@Service
public class BenchmarkService {
    public static final String SCHEMA="iqc-benchmark-pair-v1";
    private final PipelineEvaluationService evaluations;
    private final PipelineAssetService pipelines;
    private final InspectionTaskService taskService;
    private final InspectionTaskMapper tasks;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.result.llm.gateway.GatewayCallService modelCalls;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    @org.springframework.beans.factory.annotation.Autowired
    public BenchmarkService(PipelineEvaluationService evaluations,PipelineAssetService pipelines,InspectionTaskService taskService,
            InspectionTaskMapper tasks,ObjectMapper json,io.github.opensabre.iqc.result.llm.gateway.GatewayCallService modelCalls,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive){
        this.evaluations=evaluations;this.pipelines=pipelines;this.taskService=taskService;this.tasks=tasks;this.json=json;
        this.modelCalls=modelCalls;this.sensitive=sensitive;
    }
    public BenchmarkService(PipelineEvaluationService evaluations,PipelineAssetService pipelines,InspectionTaskService taskService,
            InspectionTaskMapper tasks,ObjectMapper json){this(evaluations,pipelines,taskService,tasks,json,null,null);}


    public record Target(String pipelineId,Integer pipelineVersion,Integer draftRevision) { }
    public record Create(String name,String datasetId,Integer datasetVersion,Target baseline,Target candidate,
                         BenchmarkVariableComparator.Selection selection,Integer concurrency,String requestId) { }
    public record Pair(String schemaVersion,String experimentId,String name,String datasetId,int datasetVersion,
                       String datasetHash,
                       @tools.jackson.databind.annotation.JsonSerialize(using=HttpTreeSerializer.class) JsonNode selection,
                       @tools.jackson.databind.annotation.JsonSerialize(using=HttpTreeSerializer.class) JsonNode comparison,
                       InspectionTask baseline,InspectionTask candidate) { }
    /** Field-local HTTP bridge; Jackson 2 trees must retain JSON contents under Jackson 3 MVC. */
    public static final class HttpTreeSerializer extends tools.jackson.databind.ValueSerializer<JsonNode> {
        private static final tools.jackson.databind.json.JsonMapper HTTP=tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        @Override public void serialize(JsonNode value,tools.jackson.core.JsonGenerator output,
                tools.jackson.databind.SerializationContext context) {
            output.writeTree(HTTP.readTree(value.toString()));
        }
    }
    public record Report(String schemaVersion,Pair experiment,PipelineEvaluationService.Report baseline,
                         PipelineEvaluationService.Report candidate,boolean complete,Map<String,BigDecimal> deltas,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ModelUsage modelUsage) {
        public Report(String schemaVersion,Pair experiment,PipelineEvaluationService.Report baseline,PipelineEvaluationService.Report candidate,boolean complete,Map<String,BigDecimal> deltas){
            this(schemaVersion,experiment,baseline,candidate,complete,deltas,null);
        }
    }
    public record UsageSide(int expectedSamples,int boundSamples,int unboundSamples,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.RunUsage recorded) { }
    public record ModelUsage(String schemaVersion,String scope,UsageSide baseline,UsageSide candidate) { }

    @Transactional
    public Pair create(Create request) {
        validate(request);
        String baselineRequest=sideRequest(request.requestId(),"BASELINE"),candidateRequest=sideRequest(request.requestId(),"CANDIDATE");
        String baselineId=taskService.evaluationRequestTaskId(baselineRequest),candidateId=taskService.evaluationRequestTaskId(candidateRequest);
        String requestHash=PipelineAssetService.hash(json.valueToTree(request).toString());
        var priorBaseline=tasks.selectById(baselineId); var priorCandidate=tasks.selectById(candidateId);
        if(priorBaseline!=null || priorCandidate!=null) {
            if(priorBaseline==null || priorCandidate==null) throw IqcException.invalidState("实验配对不完整，不能重新捕获另一侧输入");
            var prior=pair(baselineId);
            if(!requestHash.equals(parse(prior.baseline().getRuleSnapshotJson()).path("benchmarkSnapshot").path("requestHash").asText()))
                throw IqcException.invalidState("同一实验请求标识不能用于不同内容");
            return prior;
        }
        var baseline=resolve(request.baseline()); var candidate=resolve(request.candidate());
        var comparison=BenchmarkVariableComparator.compare(baseline.release(),candidate.release(),request.selection(),json);
        var marker=json.createObjectNode().put("schemaVersion",SCHEMA).put("experimentId",taskService.evaluationRequestTaskId(request.requestId()))
                .put("requestHash",requestHash).put("name",request.name()==null?"版本对照":request.name().trim());
        marker.set("selection",json.valueToTree(request.selection())); marker.set("comparison",json.valueToTree(comparison));
        marker.set("baselineTarget",json.valueToTree(request.baseline())); marker.set("candidateTarget",json.valueToTree(request.candidate()));
        marker.put("baselineHash",baseline.contentHash()).put("candidateHash",candidate.contentHash());
        marker.put("datasetId",request.datasetId()).put("datasetVersion",request.datasetVersion());
        var left=marker.deepCopy().put("side","BASELINE").put("peerTaskId",candidateId);
        var right=marker.deepCopy().put("side","CANDIDATE").put("peerTaskId",baselineId);
        evaluations.createBenchmarked(request.datasetId(),request.datasetVersion(),request.baseline().pipelineId(),
                request.baseline().pipelineVersion(),request.baseline().draftRevision(),marker.path("name").asText()+" · 基线",
                request.concurrency()==null?1:request.concurrency(),baselineRequest,left);
        evaluations.createBenchmarked(request.datasetId(),request.datasetVersion(),request.candidate().pipelineId(),
                request.candidate().pipelineVersion(),request.candidate().draftRevision(),marker.path("name").asText()+" · 候选",
                request.concurrency()==null?1:request.concurrency(),candidateRequest,right);
        // Recheck actual stored releases: drafts and referenced assets may have changed while resolving either side.
        return pair(baselineId);
    }
    private PipelineAssetService.Prepared resolve(Target target) {
        return target.pipelineVersion()==null?pipelines.frozenDraft(target.pipelineId(),target.draftRevision())
                :pipelines.publishedRelease(target.pipelineId(),target.pipelineVersion());
    }
    private void validate(Create request) {
        if(request==null || request.datasetId()==null || request.datasetId().isBlank() || request.datasetVersion()==null
                || request.datasetVersion()<1 || request.selection()==null || request.selection().dimension()==null
                || request.requestId()==null || !request.requestId().matches("[A-Za-z0-9_-]{16,64}")
                || request.name()!=null && request.name().length()>100
                || request.concurrency()!=null && (request.concurrency()<1 || request.concurrency()>32))
            throw IqcException.invalidArgument("实验需要精确数据集版本、单变量选择、1–32并发及16–64字符请求标识");
        for(var target:List.of(Objects.requireNonNullElse(request.baseline(),new Target(null,null,null)),
                Objects.requireNonNullElse(request.candidate(),new Target(null,null,null)))) {
            if(target.pipelineId()==null || target.pipelineId().isBlank() || (target.pipelineVersion()==null)==(target.draftRevision()==null)
                    || target.pipelineVersion()!=null && target.pipelineVersion()<1 || target.draftRevision()!=null && target.draftRevision()<1)
                throw IqcException.invalidArgument("实验两侧必须选择精确流水线发布版本或草稿修订之一");
        }
    }
    private String sideRequest(String request,String side) {
        return PipelineAssetService.hash(json.createArrayNode().add("benchmark").add(request).add(side).toString());
    }
    public Pair pair(String baselineTaskId) {
        var baseline=evaluations.context(baselineTaskId); var left=baseline.snapshot().path("benchmarkSnapshot");
        if(!SCHEMA.equals(left.path("schemaVersion").asText()) || !"BASELINE".equals(left.path("side").asText()))
            throw IqcException.notFound("基线任务没有绑定实验");
        var candidate=evaluations.context(left.path("peerTaskId").asText()); var right=candidate.snapshot().path("benchmarkSnapshot");
        if(!SCHEMA.equals(right.path("schemaVersion").asText()) || !"CANDIDATE".equals(right.path("side").asText())
                || !baselineTaskId.equals(right.path("peerTaskId").asText()) || baselineTaskId.equals(candidate.task().getId())
                || !common(left).equals(common(right)) || left.path("experimentId").asText().isBlank()
                || !left.path("requestHash").asText().matches("[a-f0-9]{64}"))
            throw IqcException.invalidState("实验双向绑定或请求身份不一致");
        var datasetLeft=baseline.snapshot().path("evaluationSnapshot"); var datasetRight=candidate.snapshot().path("evaluationSnapshot");
        for(String field:List.of("datasetId","versionNo","contentHash","sampleCount"))
            if(!datasetLeft.path(field).equals(datasetRight.path(field))) throw IqcException.invalidState("实验两侧不是同一冻结数据集");
        if(!left.path("datasetId").equals(datasetLeft.path("datasetId")) || left.path("datasetVersion").asInt()!=datasetLeft.path("versionNo").asInt()
                || !Objects.equals(baseline.task().getConcurrencyLimit(),candidate.task().getConcurrencyLimit())
                || baseline.task().getConcurrencyLimit()==null || baseline.task().getConcurrencyLimit()<1 || baseline.task().getConcurrencyLimit()>32)
            throw IqcException.invalidState("实验数据集或执行并发发生变化");
        checkTarget(left.path("baselineTarget"),baseline.snapshot().path("pipelineSnapshot"),left.path("baselineHash").asText());
        checkTarget(left.path("candidateTarget"),candidate.snapshot().path("pipelineSnapshot"),left.path("candidateHash").asText());
        var selection=read(left.path("selection"),BenchmarkVariableComparator.Selection.class);
        var comparison=json.valueToTree(BenchmarkVariableComparator.compare(baseline.prepared().release(),candidate.prepared().release(),selection,json));
        if(!comparison.equals(left.path("comparison"))) throw IqcException.invalidState("实验单变量校验与冻结记录不一致");
        return new Pair(SCHEMA,left.path("experimentId").asText(),left.path("name").asText(),datasetLeft.path("datasetId").asText(),
                datasetLeft.path("versionNo").asInt(),datasetLeft.path("contentHash").asText(),left.path("selection").deepCopy(),comparison,
                baseline.task(),candidate.task());
    }
    private void checkTarget(JsonNode target,JsonNode prepared,String expectedHash) {
        if(!target.path("pipelineId").equals(prepared.path("pipelineId")) || !expectedHash.equals(prepared.path("contentHash").asText())
                || !optional(target.path("pipelineVersion")).equals(optional(prepared.path("versionNo")))
                || !optional(target.path("draftRevision")).equals(optional(prepared.path("draftRevision"))))
            throw IqcException.invalidState("实验目标版本或流水线摘要不一致");
    }
    private JsonNode optional(JsonNode value) {
        return value.isMissingNode() || value.isNull()?json.getNodeFactory().nullNode():value;
    }
    private ObjectNode common(JsonNode marker) {
        if(!marker.isObject()) throw IqcException.invalidState("实验绑定协议无效");
        var common=(ObjectNode)marker.deepCopy(); common.remove(List.of("side","peerTaskId")); return common;
    }
    public Report report(String baselineId,String baselineExecutionId,String candidateExecutionId) {
        return report(baselineId,baselineExecutionId,candidateExecutionId,false);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Report report(String baselineId,String baselineExecutionId,String candidateExecutionId,boolean includeModelUsage) {
        if(includeModelUsage){
            if(sensitive==null||modelCalls==null)throw IqcException.invalidState("对照调用统计授权或汇总组件不可用");
            sensitive.current(Set.of("iqc:benchmark:report:view","iqc:task:model-calls:view"));
        }
        if(baselineExecutionId==null || baselineExecutionId.isBlank() || candidateExecutionId==null || candidateExecutionId.isBlank())
            throw IqcException.invalidArgument("对照报告必须固定双方执行轮次");
        var pair=pair(baselineId);
        var baseline=evaluations.report(pair.baseline().getId(),baselineExecutionId);
        var candidate=evaluations.report(pair.candidate().getId(),candidateExecutionId);
        if(!pair.datasetHash().equals(baseline.datasetHash()) || !pair.datasetHash().equals(candidate.datasetHash())
                || baseline.expectedSamples()!=candidate.expectedSamples()) throw IqcException.invalidState("对照报告金标分母不一致");
        var delta=new LinkedHashMap<String,BigDecimal>();
        delta.put("precision",difference(candidate.findingMetrics().aggregate().precision(),baseline.findingMetrics().aggregate().precision()));
        delta.put("recall",difference(candidate.findingMetrics().aggregate().recall(),baseline.findingMetrics().aggregate().recall()));
        delta.put("f1",difference(candidate.findingMetrics().aggregate().f1(),baseline.findingMetrics().aggregate().f1()));
        delta.put("coverage",difference(candidate.findingMetrics().aggregate().coverage(),baseline.findingMetrics().aggregate().coverage()));
        delta.put("scoreMae",difference(candidate.businessMetrics().meanAbsoluteScoreError(),baseline.businessMetrics().meanAbsoluteScoreError()));
        delta.put("highRiskRecall",difference(candidate.businessMetrics().highRiskRecall(),baseline.businessMetrics().highRiskRecall()));
        delta.put("regressionPassRate",difference(candidate.regressionMetrics().passRate(),baseline.regressionMetrics().passRate()));
        delta.put("structuredSuccessRate",difference(candidate.structuredMetrics().successRate(),baseline.structuredMetrics().successRate()));
        delta.put("p95Ms",candidate.latencyMetrics().p95Ms()==null || baseline.latencyMetrics().p95Ms()==null?null:
                BigDecimal.valueOf(candidate.latencyMetrics().p95Ms()).subtract(BigDecimal.valueOf(baseline.latencyMetrics().p95Ms())));
        return new Report("iqc-benchmark-report-v1",pair,baseline,candidate,baseline.complete()&&candidate.complete(),Collections.unmodifiableMap(delta),includeModelUsage?new ModelUsage("iqc-benchmark-model-usage-v1",
                "RECORDED_CALLS_OF_VALIDATED_SAMPLE_SOURCE_RUNS",usageSide(pair.baseline().getId(),baseline),usageSide(pair.candidate().getId(),candidate)):null);
    }
    private UsageSide usageSide(String taskId,PipelineEvaluationService.Report report){
        var prepared=evaluations.context(taskId).prepared();
        var references=new ArrayList<io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.RunReference>();
        for(var member:prepared.dataset().members()){
            String executionId=report.sourceExecutionIds().get(member.sampleId());
            if(executionId!=null)references.add(new io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.RunReference(taskId,executionId,member.gold().conversationId()));
        }
        if(references.size()!=report.sourceExecutionIds().size()||prepared.dataset().members().size()!=report.expectedSamples())
            throw IqcException.invalidState("对照调用统计的样本来源范围不一致");
        return new UsageSide(report.expectedSamples(),references.size(),report.expectedSamples()-references.size(),modelCalls.taskRunUsage(references));
    }
    private BigDecimal difference(BigDecimal candidate,BigDecimal baseline){return candidate==null || baseline==null?null:candidate.subtract(baseline);}
    private JsonNode parse(String value) {
        if(value==null || value.getBytes(StandardCharsets.UTF_8).length>16*1024*1024) throw IqcException.invalidState("实验快照为空或超限");
        try{return PipelineAssetService.readFrozenTree(json,value);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw IqcException.invalidState("实验快照格式无效");}
    }
    private <T>T read(JsonNode value,Class<T> type) {
        try{return json.treeToValue(value,type);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw IqcException.invalidState("实验选择协议无效");}
    }
}
