package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.BusinessReviewSampleService;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Local mined candidates use the original production locks, sample pool and action receipt identity. */
@Service @RequiredArgsConstructor
public class MinedEvaluationCandidateService {
    public static final int ADAPTER_VERSION=2;
    private final ActionProductionSourceValidator sources;
    private final QualitySampleMapper samples;
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String lookup(ActionAdapter.Request request,JsonNode config){
        authorize(request);MinedCandidateSelection.validateConfiguration(config);
        var sample=samples.selectById(id(request));return sample==null?null:confirm(sample,request,config).getId();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String create(ActionAdapter.Request request,JsonNode config){
        authorize(request);scope.requireScheduledResources(Set.of("iqc:sample:manage"));
        MinedCandidateSelection.validateConfiguration(config);
        var existing=samples.selectById(id(request));if(existing!=null)return confirm(existing,request,config).getId();
        var ref=request.source();
        var validated=sources.validate(new ActionProductionSourceValidator.Reference(ref.taskId(),ref.executionId(),ref.conversationId(),ref.resultId(),ref.owner()));
        if(!Set.of("RUNNING","SUCCEEDED","PARTIAL_FAILED","FAILED").contains(Objects.toString(validated.taskStatus(),""))
                ||!request.inputSnapshotHash().equals(validated.inputSnapshotHash())||!request.inputSnapshotJson().equals(validated.inputSnapshotJson()))
            throw IqcException.invalidState("挖掘候选原生产来源已变化或不可验证");
        existing=locked(id(request));if(existing!=null)return confirm(existing,request,config).getId();
        try{
            var policy=ActionFrozenPolicy.readFrozen(json,request.policySnapshotJson(),request.inputSnapshotJson());
            if(!json.writeValueAsString(validated.policy()).equals(request.policySnapshotJson()))throw IqcException.invalidState("挖掘候选冻结政策不一致");
            var action=policy.actions().stream().filter(value->ref.actionId().equals(value.actionId())).findFirst().orElseThrow();
            if(action.type()!=ActionExecutionContract.Type.ADD_TO_EVALUATION_CANDIDATE||action.effectiveAdapterVersion()!=ADAPTER_VERSION
                    ||!action.configuration().equals(config)||ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)
                throw IqcException.invalidState("挖掘候选动作或筛选条件不一致");
            var reasons=MinedCandidateSelection.reasons(validated.context(),config);
            if(reasons.isEmpty())throw IqcException.invalidState("该生产结果未命中挖掘条件");
            var task=tasks.selectById(ref.taskId());
            JsonNode envelope=json.readTree(validated.inputSnapshotJson());
            if(task==null||!Objects.equals(task.getRuleSnapshotJson(),envelope.path("taskSnapshotJson").textValue()))throw IqcException.invalidState("挖掘候选原任务不一致");
            var snapshot=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());
            var release=json.treeToValue(snapshot.path("pipelineSnapshot").path("release"),io.github.opensabre.iqc.pipelineasset.PipelineRelease.class);
            var trace=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenValue(json,envelope.path("traceJson").textValue(),io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation.class,release);
            var frozen=io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.validatedExecutionInput(task,snapshot,trace,ref.conversationId(),json);
            var input=json.createObjectNode().put("schemaVersion",BusinessReviewSampleService.EXACT_INPUT_SCHEMA).put("conversationId",ref.conversationId());
            input.set("businessContext",frozen.get("businessContext"));var segments=input.putArray("segments");
            for(var value:frozen.path("messages"))segments.add(BusinessReviewSampleService.candidateSegment(json.treeToValue(value,io.github.opensabre.iqc.conversation.model.ConversationMessage.class),json));
            var lineage=new MinedCandidateLineage(MinedCandidateLineage.SCHEMA,ref.resultId(),ref.taskId(),ref.executionId(),ref.conversationId(),ref.owner(),validated.ownerGroupId(),
                    request.inputSnapshotHash(),BusinessReviewSampleService.candidateInputHash(input),trace.inputHash(),ref.actionPolicyHash(),MinedCandidateSelection.STRATEGY,MinedCandidateSelection.VERSION,reasons,fingerprint(request,config));
            var sample=new QualitySample();sample.setId(id(request));sample.setName(name(request,config));sample.setSampleType(MinedCandidateLineage.SAMPLE_TYPE);
            sample.setSourceResultId(ref.resultId());sample.setConversationId(ref.conversationId());sample.setMessageId(null);
            sample.setCreatedBy(ref.owner());sample.setOwnerGroupId(validated.ownerGroupId());sample.setStatus("CANDIDATE");
            sample.setContentSnapshot(bounded(input));sample.setExpectedJson(bounded(json.valueToTree(lineage)));sample.setTagsJson(tags(config).toString());
            MinedCandidateLineage.validate(sample,json);scope.requireScheduledResources(Set.of("iqc:sample:manage"));
            try{if(samples.insert(sample)!=1)throw IqcException.invalidState("自动候选未形成持久产物");}catch(org.springframework.dao.DuplicateKeyException conflict){var winner=locked(id(request));if(winner==null)throw conflict;return confirm(winner,request,config).getId();}
            return sample.getId();
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw IqcException.invalidState("挖掘候选冻结来源格式无效");}
    }
    private void authorize(ActionAdapter.Request request){
        if(request==null||!Objects.equals(scope.owner(),request.source().owner())||"system".equals(scope.owner())
                ||request.adapter().type()!=ActionExecutionContract.Type.ADD_TO_EVALUATION_CANDIDATE||request.adapter().version()!=ADAPTER_VERSION)
            throw IqcException.accessDenied("挖掘候选负责人或适配版本不一致");
    }
    private QualitySample locked(String id){return samples.selectOne(Wrappers.<QualitySample>lambdaQuery().eq(QualitySample::getId,id).last("FOR UPDATE"));}
    private QualitySample confirm(QualitySample sample,ActionAdapter.Request request,JsonNode config){
        var source=MinedCandidateLineage.validate(sample,json);var ref=request.source();
        if(!Objects.equals(source.owner(),ref.owner())||!Objects.equals(source.conversationId(),ref.conversationId())
                ||!MinedCandidateSelection.STRATEGY.equals(source.miningStrategy())||source.miningVersion()!=MinedCandidateSelection.VERSION
                ||!Objects.equals(source.taskId(),ref.taskId())||!Objects.equals(source.executionId(),ref.executionId())
                ||!Objects.equals(source.sourceResultId(),ref.resultId())||!Objects.equals(source.sourceHash(),request.inputSnapshotHash())
                ||!Objects.equals(source.miningPolicyHash(),ref.actionPolicyHash())||!Objects.equals(source.requestFingerprint(),fingerprint(request,config))
                ||!Objects.equals(sample.getName(),name(request,config))||!Objects.equals(sample.getTagsJson(),tags(config).toString()))
            throw IqcException.invalidState("候选首次捕获与原动作请求不一致");
        return sample;
    }
    private String id(ActionAdapter.Request request){return "qm-"+request.idempotencyKey().substring(0,60);}
    private String fingerprint(ActionAdapter.Request request,JsonNode config){return SelectorAssetService.hash(json.valueToTree(Map.of("source",request.source(),"inputHash",request.inputSnapshotHash(),"configuration",config)).toString());}
    private String name(ActionAdapter.Request request,JsonNode config){return config.has("name")?config.path("name").textValue().trim():"自动候选-"+request.source().resultId();}
    private JsonNode tags(JsonNode config){return config.has("tags")?config.get("tags"):json.createArrayNode();}
    private String bounded(JsonNode value){String body=value.toString();if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4*1024*1024)throw IqcException.invalidState("自动候选快照超过4MiB，不能截断");return body;}
}
