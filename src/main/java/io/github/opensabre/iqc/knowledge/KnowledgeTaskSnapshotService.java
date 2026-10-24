package io.github.opensabre.iqc.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.agent.AgentConfiguration;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Private task material, never embedded in a shared Agent or Pipeline release. */
@Service
@RequiredArgsConstructor
public class KnowledgeTaskSnapshotService {
    public static final String SCHEMA="iqc-knowledge-task-v1";
    private static final int MAX_BYTES=8*1024*1024;
    private final KnowledgeBaseService knowledge;
    private final SensitiveReadAccessService access;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.MANDATORY)
    public void freeze(InspectionTask task){
        var root=parse(task.getRuleSnapshotJson(),json);
        var references=new ArrayList<KnowledgeBaseService.Binding>();
        collect(parse(task.getAgentSnapshotJson(),json),references,json);
        for(var node:root.path("pipelineSnapshot").path("release").path("pipeline").path("nodes"))
            collect(node.path("config").path("agentSnapshot"),references,json);
        if(references.isEmpty()){
            if(root.has("knowledgeInputs"))throw IqcException.invalidState("无引用任务不能携带知识库正文");
            return;
        }
        if(root.has("knowledgeInputs"))throw IqcException.invalidState("任务知识库正文不能由调用者提供");
        access.current(Set.of("iqc:knowledge:version:view"));
        var sources=knowledge.publishedForTask(references);
        String group=task.getOwnerGroupId();
        for(var source:sources)if(!Objects.equals(group,source.ownerGroupId()))group=null;
        // A null source group or a null input group remains private, even for an all-scope actor.
        task.setOwnerGroupId(group);
        var body=json.createObjectNode().put("owner",scope.owner()).put("ownerGroupId",group);
        body.set("sources",json.valueToTree(sources));
        var envelope=json.createObjectNode().put("schemaVersion",SCHEMA)
                .put("hashAlgorithm","selector-decimal-sha256-v1")
                .put("contentHash",SelectorAssetService.hash(body.toString()));
        envelope.set("body",body);
        if(envelope.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw IqcException.invalidArgument("任务知识库正文冻结超过8MiB，请缩小引用语料");
        ObjectNode result=root.isObject()?(ObjectNode)root.deepCopy():json.createObjectNode().set("rules",root);
        result.set("knowledgeInputs",envelope);
        task.setRuleSnapshotJson(result.toString());
    }

    /** Validate the persisted original before queueing, resuming or executing; never resolve mutable corpus here. */
    public static List<KnowledgeBaseService.Prepared> verifiedSources(InspectionTask task,ObjectMapper json){
        var root=parse(task.getRuleSnapshotJson(),json);
        var references=new ArrayList<KnowledgeBaseService.Binding>();
        collect(parse(task.getAgentSnapshotJson(),json),references,json);
        for(var node:root.path("pipelineSnapshot").path("release").path("pipeline").path("nodes"))
            collect(node.path("config").path("agentSnapshot"),references,json);
        if(references.isEmpty()){
            if(root.has("knowledgeInputs"))throw IqcException.invalidState("无引用任务不能携带知识库正文");
            return List.of();
        }
        try{
            var envelope=root.path("knowledgeInputs");var body=envelope.path("body");
            fields(envelope,Set.of("schemaVersion","hashAlgorithm","contentHash","body"));
            fields(body,Set.of("owner","ownerGroupId","sources"));
            String group=body.path("ownerGroupId").isNull()?null:body.path("ownerGroupId").textValue();
            if(!SCHEMA.equals(envelope.path("schemaVersion").asText())
                    ||!"selector-decimal-sha256-v1".equals(envelope.path("hashAlgorithm").asText())
                    ||envelope.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES
                    ||!SelectorAssetService.hash(body.toString()).equals(envelope.path("contentHash").asText())
                    ||task.getCreatedBy()==null||!task.getCreatedBy().equals(body.path("owner").textValue())
                    ||(!body.path("ownerGroupId").isNull()&&!body.path("ownerGroupId").isTextual())
                    ||(task.getOwnerGroupId()!=null&&!task.getOwnerGroupId().equals(group)))throw new IllegalArgumentException();
            var expected=new java.util.HashSet<>(references);
            if(expected.size()>400)throw new IllegalArgumentException();
            var sources=body.path("sources");if(!sources.isArray()||sources.size()!=expected.size())throw new IllegalArgumentException();
            var result=new ArrayList<KnowledgeBaseService.Prepared>();
            for(var source:sources){
                var exact=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
                var value=exact.treeToValue(source,KnowledgeBaseService.Prepared.class);
                var published=value.snapshot();
                if(published==null||!expected.remove(new KnowledgeBaseService.Binding(published.knowledgeBaseId(),published.versionNo(),value.versionId(),value.contentHash()))
                        ||(value.ownerGroupId()!=null&&!value.ownerGroupId().equals(published.ownerGroupId()))
                        ||(group!=null&&!group.equals(value.ownerGroupId())))throw new IllegalArgumentException();
                var version=new io.github.opensabre.iqc.knowledge.model.KnowledgeBaseVersion();
                version.setId(value.versionId());version.setKnowledgeBaseId(published.knowledgeBaseId());
                version.setVersionNo(published.versionNo());version.setSourceDraftRevision(published.sourceDraftRevision());
                version.setSnapshotJson(json.writeValueAsString(published));version.setContentHash(value.contentHash());
                version.setCreatedBy(published.publishedBy());
                KnowledgeBaseService.readPublishedEvidence(version,json);
                result.add(value);
            }
            if(!expected.isEmpty())throw new IllegalArgumentException();
            return List.copyOf(result);
        }catch(Exception invalid){throw IqcException.invalidState("任务知识库正文归属、引用覆盖或摘要无效");}
    }
    private static void fields(JsonNode value,Set<String> names){
        if(!value.isObject()||value.size()!=names.size())throw new IllegalArgumentException();
        value.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw new IllegalArgumentException();});
    }

    public static boolean requiresSnapshot(InspectionTask task,ObjectMapper json){
        var root=parse(task.getRuleSnapshotJson(),json);
        if(root.has("knowledgeInputs"))return true;
        var references=new ArrayList<KnowledgeBaseService.Binding>();
        collect(parse(task.getAgentSnapshotJson(),json),references,json);
        for(var node:root.path("pipelineSnapshot").path("release").path("pipeline").path("nodes"))
            collect(node.path("config").path("agentSnapshot"),references,json);
        return !references.isEmpty();
    }

    private static void collect(JsonNode agent,List<KnowledgeBaseService.Binding> references,ObjectMapper json){
        var config=agent.path("configJson");
        if(config.isTextual())config=parse(config.textValue(),json);
        var values=config.path("knowledgeAssets");
        if(values.isMissingNode()||values.isNull()||(values.isArray()&&values.isEmpty()))return;
        try{
            var parsed=json.treeToValue(config,AgentConfiguration.class).validated();
            if(parsed.knowledgeAssets()==null||parsed.knowledgeAssets().isEmpty())throw new IllegalArgumentException();
            for(var value:parsed.knowledgeAssets())references.add(new KnowledgeBaseService.Binding(
                    value.knowledgeBaseId(),value.versionNo(),value.versionId(),value.contentHash()));
        }catch(Exception invalid){throw IqcException.invalidState("任务Agent知识库精确引用无效");}
    }
    private static JsonNode parse(String value,ObjectMapper json){
        if(value==null||value.isBlank())return json.createObjectNode();
        try(var parser=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(value)){
            JsonNode result=parser.readValueAsTree();if(result==null||parser.nextToken()!=null)throw new IllegalArgumentException();return result;
        }
        catch(Exception invalid){throw IqcException.invalidState("任务知识库来源快照无效");}
    }
}
