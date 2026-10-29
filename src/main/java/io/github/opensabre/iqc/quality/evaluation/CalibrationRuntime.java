package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipeline.NodeResult;
import io.github.opensabre.iqc.pipeline.PipelineRuntime;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.math.BigDecimal;
import java.util.*;

/** Applies original frozen coefficients before downstream consumers and verifies the persisted transformation. */
public final class CalibrationRuntime {
    private CalibrationRuntime(){ }
    public static final String KEY="iqcConfidenceCalibration";
    public static final String SCHEMA="iqc-confidence-transform-v1";
    public static NodeResult apply(InspectionTask task,io.github.opensabre.iqc.pipeline.PipelineDefinition.Node node,NodeResult raw,ObjectMapper json){
        var sources=CalibrationTaskSnapshotService.verifiedSources(task,json);
        var source=sources.stream().filter(value->node.nodeId().equals(value.reference().nodeId())).findFirst().orElse(null);
        if(source==null||raw.status()!=NodeResult.Status.SUCCESS)return raw;
        if(raw.metrics().containsKey(KEY))throw IqcException.invalidState("校准转换不能重复应用");
        if(!source.artifact().source().nodeHash().equals(SelectorAssetService.hash(json.valueToTree(node).toString())))
            throw IqcException.invalidState("校准节点定义与原任务不一致");
        var fact=single(source,raw,node);
        BigDecimal original=rawConfidence(fact,raw,false);
        if(original!=null&&fact.state()==Finding.State.KNOWN)requireIdentity(source.artifact().source(),raw,fact);
        BigDecimal calibrated=fact.state()==Finding.State.KNOWN?source.artifact().apply(original):null;
        var proof=json.createObjectNode().put("schemaVersion",SCHEMA).put("artifactHash",source.artifact().contentHash())
                .put("findingId",fact.id()).put("rawConfidence",original).put("calibratedConfidence",calibrated)
                .put("supported",calibrated!=null);
        var metrics=new LinkedHashMap<>(raw.metrics());metrics.put(KEY,proof);
        var result=new NodeResult(raw.status(),List.of(withConfidence(fact,calibrated)),raw.variables(),metrics,raw.error());
        verifyNode(source,result,node);return result;
    }
    public static void verify(InspectionTask task,PipelineRuntime.Run run,ObjectMapper json){
        var sources=CalibrationTaskSnapshotService.verifiedSources(task,json);
        if(sources.isEmpty())return;
        if(run==null)throw IqcException.invalidState("校准任务缺少原运行轨迹");
        var byNode=new HashMap<String,PipelineEvaluationService.PreparedCalibration>();
        sources.forEach(value->byNode.put(value.reference().nodeId(),value));
        var definitions=new HashMap<String,io.github.opensabre.iqc.pipeline.PipelineDefinition.Node>();
        try{
            var root=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());
            var graph=json.treeToValue(root.path("pipelineSnapshot").path("release").path("pipeline"),io.github.opensabre.iqc.pipeline.PipelineDefinition.class);
            for(var node:graph.nodes())if(definitions.putIfAbsent(node.nodeId(),node)!=null)throw new IllegalArgumentException();
        }catch(Exception invalid){throw IqcException.invalidState("校准任务检测图无法复核");}
        var traced=new HashMap<String,Finding>();
        for(var trace:run.trace()){
            var source=byNode.get(trace.nodeId());
            if(source==null){if(trace.result().metrics().containsKey(KEY))throw IqcException.invalidState("未引用节点不能携带校准证明");continue;}
            if(trace.result().status()!=NodeResult.Status.SUCCESS){
                if(trace.result().metrics().containsKey(KEY))throw IqcException.invalidState("失败节点不能宣称已转换");
                continue;
            }
            verifyNode(source,trace.result(),definitions.get(trace.nodeId()));
            for(var fact:trace.result().findings()){
                if(fact.source().runIndex()!=trace.attempt())throw IqcException.invalidState("校准事实尝试轮次与轨迹不一致");
                if(traced.putIfAbsent(fact.id(),fact)!=null)throw IqcException.invalidState("校准节点轨迹事实重复");
            }
        }
        for(var fact:run.findings())if(byNode.containsKey(fact.source().nodeId())){
            var expected=traced.remove(fact.id());
            if(expected==null||!SelectorAssetService.hash(json.valueToTree(expected).toString()).equals(
                    SelectorAssetService.hash(json.valueToTree(fact).toString())))throw IqcException.invalidState("运行事实与校准轨迹不一致");
        }
        if(!traced.isEmpty())throw IqcException.invalidState("校准轨迹事实未进入原运行结果");
    }
    private static void verifyNode(PipelineEvaluationService.PreparedCalibration source,NodeResult result,io.github.opensabre.iqc.pipeline.PipelineDefinition.Node node){
        var fact=single(source,result,node);var proof=result.metrics().get(KEY);
        if(proof==null||!proof.isObject()||proof.size()!=6||!SCHEMA.equals(proof.path("schemaVersion").asText())
                ||!source.artifact().contentHash().equals(proof.path("artifactHash").textValue())
                ||!fact.id().equals(proof.path("findingId").textValue())||!proof.path("supported").isBoolean()
                ||!proof.has("rawConfidence")||!proof.has("calibratedConfidence"))throw IqcException.invalidState("校准证明字段或来源无效");
        BigDecimal original=probability(proof.get("rawConfidence"));
        if("LLM".equals(fact.source().engineType())&&!same(original,rawConfidence(fact,result,false)))
            throw IqcException.invalidState("校准原始值与模型逻辑调用不一致");
        if(original!=null&&fact.state()==Finding.State.KNOWN)requireIdentity(source.artifact().source(),result,fact);
        BigDecimal expected=fact.state()==Finding.State.KNOWN?source.artifact().apply(original):null;
        if(!same(expected,probability(proof.get("calibratedConfidence")))||!same(expected,fact.confidence())
                ||proof.path("supported").booleanValue()!=(expected!=null))throw IqcException.invalidState("校准值不符合原冻结系数");
    }
    private static Finding single(PipelineEvaluationService.PreparedCalibration source,NodeResult result,io.github.opensabre.iqc.pipeline.PipelineDefinition.Node node){
        if(result.findings().size()!=1)throw IqcException.invalidState("校准节点须具有唯一类型化事实");
        var fact=result.findings().getFirst();
        if(node==null||!source.reference().nodeId().equals(fact.source().nodeId())||!source.artifact().source().findingCode().equals(fact.code())
                ||!Set.of("LLM","SMALL_MODEL").contains(fact.source().engineType())||!node.type().equals(fact.source().engineType())
                ||!node.version().equals(fact.source().engineVersion()))throw IqcException.invalidState("校准事实不属于原节点");
        return fact;
    }
    /** Reuse the admitted invocation fingerprint; input hashes vary across fitting samples. */
    public static io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter.InvocationFingerprint invocationIdentity(NodeResult result,Finding fact){
        if(result==null||result.status()!=NodeResult.Status.SUCCESS||result.findings().size()!=1)return null;
        var traced=result.findings().getFirst();
        if(!traced.id().equals(fact.id())||!traced.source().equals(fact.source())||traced.state()!=fact.state()
                ||traced.valueType()!=fact.valueType()||!FindingEvaluationMetrics.equal(traced.value(),fact.value()))return null;
        JsonNode value;
        if("LLM".equals(fact.source().engineType())){
            var metrics=result.metrics().get("llmStructuredOutput");
            if(metrics==null||!"iqc-llm-structured-metrics-v1".equals(metrics.path("schemaVersion").asText())
                    ||!one(metrics.path("logicalInvocations"))||!one(metrics.path("validOutputs"))
                    ||!metrics.path("invalidOrFailedOutputs").isIntegralNumber()||metrics.path("invalidOrFailedOutputs").asLong()!=0)return null;
            var values=metrics.path("invocationFingerprints");if(!values.isArray()||values.size()!=1)return null;
            value=values.get(0);
        }else if("SMALL_MODEL".equals(fact.source().engineType()))value=result.metrics().get("modelInvocation");
        else return null;
        if(value==null||!value.isObject()||value.size()!=4)return null;
        for(String key:List.of("schemaVersion","modelKey","inputHash","deploymentRef"))if(!value.path(key).isTextual())return null;
        try{
            var identity=new io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter.InvocationFingerprint(value.path("schemaVersion").textValue(),
                    value.path("modelKey").textValue(),value.path("inputHash").textValue(),value.path("deploymentRef").textValue());
            return "SMALL_MODEL".equals(fact.source().engineType())&&!identity.inputHash().equals(fact.source().inputHash())?null:identity;
        }catch(IllegalArgumentException invalid){return null;}
    }
    private static void requireIdentity(EmpiricalConfidenceCalibration.Source source,NodeResult result,Finding fact){
        var current=invocationIdentity(result,fact);
        if(source.modelKey()==null||current==null||!source.modelKey().equals(current.modelKey())||!source.deploymentRef().equals(current.deploymentRef()))
            throw IqcException.invalidState("当前模型或部署调用身份与校准拟合来源不一致或无法测量");
    }
    /** Diagnostic readers must retain the pre-transform signal rather than fit calibrated values again. */
    public static BigDecimal rawConfidence(Finding fact,NodeResult result,boolean transformed){
        if("LLM".equals(fact.source().engineType())){
            var metrics=result.metrics().get("llmStructuredOutput");
            if(metrics==null||!"iqc-llm-structured-metrics-v1".equals(metrics.path("schemaVersion").asText())
                    ||!one(metrics.path("logicalInvocations"))||!one(metrics.path("validOutputs")))return null;
            return probability(metrics.get("reportedConfidence"));
        }
        if(transformed){
            var proof=result.metrics().get(KEY);
            if(proof==null||!SCHEMA.equals(proof.path("schemaVersion").asText()))return null;
            return probability(proof.get("rawConfidence"));
        }
        return fact.confidence();
    }
    private static boolean one(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()==1;}
    private static BigDecimal probability(JsonNode value){
        if(value==null||value.isNull())return null;
        if(!value.isNumber()||!Double.isFinite(value.doubleValue())||value.decimalValue().signum()<0||value.decimalValue().compareTo(BigDecimal.ONE)>0)
            throw IqcException.invalidState("校准轨迹置信度须为0到1数值或null");
        return value.decimalValue();
    }
    private static boolean same(BigDecimal left,BigDecimal right){return left==null?right==null:right!=null&&left.compareTo(right)==0;}
    private static Finding withConfidence(Finding fact,BigDecimal value){
        return new Finding(fact.id(),fact.code(),fact.category(),fact.valueType(),fact.value(),fact.state(),value,
                fact.severity(),fact.reason(),fact.evidence(),fact.source());
    }
}
