package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Private fitted coefficients belong to a task, never the shared Pipeline catalog. */
@Service @RequiredArgsConstructor
public class CalibrationTaskSnapshotService {
    public static final String SCHEMA="iqc-calibration-task-v1";
    private static final int MAX_BYTES=1024*1024;
    private final ObjectProvider<PipelineEvaluationService> evaluations;
    private final SensitiveReadAccessService access;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.MANDATORY)
    public void freeze(InspectionTask task){
        if(!marked(task))return;
        var root=parse(task,json);
        if(root.has("calibrationInputs"))throw IqcException.invalidState("调用者不能提供校准系数信封");
        var references=references(root,json);if(references.isEmpty())return;
        var target=graph(root,json);
        access.current(Set.of("iqc:evaluation:report:view"));
        var sources=evaluations.getObject().calibrationsForTask(references,target);
        validateSources(references,sources,target,json);
        String group=task.getOwnerGroupId();
        for(var source:sources)if(!Objects.equals(group,source.ownerGroupId()))group=null;
        task.setOwnerGroupId(group);
        var body=json.createObjectNode().put("owner",scope.owner()).put("ownerGroupId",group)
                .put("targetGraphHash",SelectorAssetService.hash(json.valueToTree(target).toString()));
        body.set("sources",json.valueToTree(sources));
        var envelope=json.createObjectNode().put("schemaVersion",SCHEMA).put("hashAlgorithm","selector-decimal-sha256-v1")
                .put("contentHash",SelectorAssetService.hash(body.toString()));
        envelope.set("body",body);
        if(envelope.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw IqcException.invalidArgument("任务校准系数信封超过1MiB");
        if(!(root instanceof ObjectNode result))throw IqcException.invalidState("校准任务根快照须为对象");
        result.set("calibrationInputs",envelope);task.setRuleSnapshotJson(result.toString());
    }
    public static boolean requiresSnapshot(InspectionTask task,ObjectMapper json){
        if(!marked(task))return false;
        var root=parse(task,json);return root.has("calibrationInputs")||!references(root,json).isEmpty();
    }
    public static List<PipelineEvaluationService.PreparedCalibration> verifiedSources(InspectionTask task,ObjectMapper json){
        if(!marked(task))return List.of();
        var root=parse(task,json);var references=references(root,json);
        if(references.isEmpty()){
            if(root.has("calibrationInputs"))throw IqcException.invalidState("无校准引用不能携带系数信封");
            return List.of();
        }
        try{
            var envelope=root.path("calibrationInputs");var body=envelope.path("body");
            fields(envelope,Set.of("schemaVersion","hashAlgorithm","contentHash","body"));
            fields(body,Set.of("owner","ownerGroupId","targetGraphHash","sources"));
            String group=body.path("ownerGroupId").isNull()?null:body.path("ownerGroupId").textValue();
            var target=graph(root,json);
            if(!SCHEMA.equals(envelope.path("schemaVersion").asText())||!"selector-decimal-sha256-v1".equals(envelope.path("hashAlgorithm").asText())
                    ||envelope.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES
                    ||!SelectorAssetService.hash(body.toString()).equals(envelope.path("contentHash").asText())
                    ||task.getCreatedBy()==null||!task.getCreatedBy().equals(body.path("owner").textValue())
                    ||(!body.path("ownerGroupId").isNull()&&!body.path("ownerGroupId").isTextual())
                    ||(task.getOwnerGroupId()!=null&&!task.getOwnerGroupId().equals(group))
                    ||!SelectorAssetService.hash(json.valueToTree(target).toString()).equals(body.path("targetGraphHash").asText()))throw new IllegalArgumentException();
            var values=body.path("sources");if(!values.isArray()||values.size()!=references.size())throw new IllegalArgumentException();
            var sources=new ArrayList<PipelineEvaluationService.PreparedCalibration>();
            for(var value:values){
                var source=exact(json).treeToValue(value,PipelineEvaluationService.PreparedCalibration.class);
                if(group!=null&&!group.equals(source.ownerGroupId()))throw new IllegalArgumentException();
                sources.add(source);
            }
            validateSources(references,sources,target,json);return List.copyOf(sources);
        }catch(Exception invalid){throw IqcException.invalidState("原任务校准系数归属、检测图或来源摘要无效");}
    }
    /** Staged references cannot silently run with untransformed raw confidence. */
    public static void requireRunnable(InspectionTask task,ObjectMapper json){
        if(!verifiedSources(task,json).isEmpty())throw IqcException.invalidState("校准系数运行转换尚未接入");
    }
    private static void validateSources(List<EmpiricalConfidenceCalibration.Reference> references,
            List<PipelineEvaluationService.PreparedCalibration> sources,PipelineDefinition target,ObjectMapper json){
        if(sources==null||sources.size()!=references.size())throw IqcException.invalidState("校准来源覆盖不完整");
        var expected=new HashSet<>(references);
        for(var source:sources){
            if(source==null||!expected.remove(source.reference())||source.artifact()==null||source.owner()==null||source.owner().isBlank())
                throw IqcException.invalidState("校准来源重复、缺少归属或未精确绑定");
            var reference=source.reference();var artifact=source.artifact();var origin=artifact.source();
            var node=target.nodes().stream().filter(value->reference.nodeId().equals(value.nodeId())).findFirst()
                    .orElseThrow(()->IqcException.invalidState("校准目标节点不存在"));
            if(!reference.contentHash().equals(artifact.contentHash())||!reference.taskId().equals(origin.taskId())
                    ||!reference.executionId().equals(origin.executionId())||!reference.nodeId().equals(origin.nodeId())
                    ||!SelectorAssetService.hash(json.valueToTree(node).toString()).equals(origin.nodeHash()))
                throw IqcException.invalidState("校准产物与原引用或目标节点不一致");
        }
        if(!expected.isEmpty())throw IqcException.invalidState("校准来源覆盖不完整");
    }
    private static List<EmpiricalConfidenceCalibration.Reference> references(JsonNode root,ObjectMapper json){
        var fusion=root.path("pipelineSnapshot").path("release").path("fusion");var values=fusion.path("calibrationReferences");
        if(values.isMissingNode()||values.isNull()||(values.isArray()&&values.isEmpty()))return List.of();
        try{return exact(json).treeToValue(fusion,FusionPolicy.class).calibrationReferences();}
        catch(Exception invalid){throw IqcException.invalidState("流水线精确校准引用无效");}
    }
    private static PipelineDefinition graph(JsonNode root,ObjectMapper json){
        try{return exact(json).treeToValue(root.path("pipelineSnapshot").path("release").path("pipeline"),PipelineDefinition.class);}
        catch(Exception invalid){throw IqcException.invalidState("校准目标检测图无效");}
    }
    private static boolean marked(InspectionTask task){
        String value=task.getRuleSnapshotJson();return value!=null&&(value.contains("calibrationReferences")||value.contains("calibrationInputs")||value.contains("\\u"));
    }
    private static ObjectMapper exact(ObjectMapper json){return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);}
    private static JsonNode parse(InspectionTask task,ObjectMapper json){
        String value=task.getRuleSnapshotJson();
        if(value==null||value.getBytes(StandardCharsets.UTF_8).length>64*1024*1024)throw IqcException.invalidState("校准任务快照为空或超限");
        try{return PipelineAssetService.readFrozenTree(exact(json),value);}
        catch(Exception invalid){throw IqcException.invalidState("校准任务快照JSON无效");}
    }
    private static void fields(JsonNode value,Set<String> names){
        if(!value.isObject()||value.size()!=names.size())throw new IllegalArgumentException();
        value.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw new IllegalArgumentException();});
    }
}
