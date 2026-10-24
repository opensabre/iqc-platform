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
        try{var result=json.readTree(value);if(result==null)throw new IllegalArgumentException();return result;}
        catch(Exception invalid){throw IqcException.invalidState("任务知识库来源快照无效");}
    }
}
