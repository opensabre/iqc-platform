package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Server-created evidence only; never accepted as an HTTP request body. No raw account ID or request key. */
public record PipelineRolloutTaskEvidence(String schemaVersion,String taskId,String requestFingerprint,
        PipelineRolloutService.Revision revision,PipelineRolloutPolicy.Decision decision) {
    public static final String SCHEMA="iqc-pipeline-rollout-task-v1";
    public static String taskId(String actorId,String pipelineId,String requestKey,ObjectMapper json){
        if(actorId==null||!actorId.matches("[A-Za-z0-9:_-]{1,64}")||pipelineId==null||pipelineId.isBlank()
                ||requestKey==null||!requestKey.matches("[A-Za-z0-9:_-]{1,128}"))throw IqcException.invalidArgument("灰度任务身份或请求键无效");
        return "g"+PipelineAssetService.hash(json.createArrayNode().add(SCHEMA).add(actorId).add(pipelineId).add(requestKey).toString()).substring(0,63);
    }
    public static String requestFingerprint(String name,List<String> ids,Integer concurrency,ObjectMapper json){
        int parallel=concurrency==null?1:concurrency;
        if(name==null||name.isBlank()||name.length()>100||ids==null||ids.isEmpty()||ids.size()>1000||parallel<1||parallel>32
                ||ids.stream().anyMatch(id->id==null||id.isBlank()||id.length()>64||id.codePoints().anyMatch(Character::isISOControl)))
            throw IqcException.invalidArgument("灰度任务需有效名称、1至1000个会话及1至32并发");
        var body=json.createObjectNode().put("schemaVersion",SCHEMA).put("name",name).put("concurrency",parallel);
        body.set("conversationIds",json.valueToTree(ids.stream().distinct().sorted().toList()));
        return PipelineAssetService.hash(body.toString());
    }
    public JsonNode envelope(ObjectMapper json){
        var body=json.valueToTree(this);
        var envelope=json.createObjectNode();envelope.set("evidence",body);
        envelope.put("evidenceHash",PipelineAssetService.hash(body.toString()));return envelope;
    }
    public void verify(String actorId,String pipelineId,String requestKey,String fingerprint,JsonNode prepared,ObjectMapper json){
        try{
            if(!SCHEMA.equals(schemaVersion)||!Objects.equals(taskId,taskId(actorId,pipelineId,requestKey,json))
                    ||!Objects.equals(requestFingerprint,fingerprint)||revision==null||revision.snapshot()==null||decision==null
                    ||revision.id()==null||revision.id().isBlank()||revision.revisionNo()<1)throw new IllegalArgumentException();
            var source=revision.snapshot();var policy=source.policy();
            if(!"iqc-pipeline-rollout-revision-v1".equals(source.schemaVersion())||!pipelineId.equals(source.pipelineId())
                    ||source.revisionNo()!=revision.revisionNo()||policy==null||!pipelineId.equals(policy.pipelineId())
                    ||!Objects.equals(revision.contentHash(),PipelineAssetService.hash(json.valueToTree(source).toString()))
                    ||!Objects.equals(source.policyHash(),PipelineAssetService.hash(json.valueToTree(policy).toString())))throw new IllegalArgumentException();
            String cohort=policy.cohortHash(actorId,requestKey);
            int bucket=new BigInteger(cohort,16).mod(BigInteger.valueOf(10000)).intValueExact();
            boolean candidate=policy.state()==PipelineRolloutPolicy.State.ACTIVE&&bucket<policy.candidateBasisPoints();
            int version=candidate?policy.candidateVersionNo():policy.baselineVersionNo();
            String releaseHash=candidate?source.candidateReleaseHash():source.baselineReleaseHash();
            if(!PipelineRolloutPolicy.SCHEMA.equals(decision.schemaVersion())||!pipelineId.equals(decision.pipelineId())
                    ||!Objects.equals(decision.policyHash(),source.policyHash())||!cohort.equals(decision.cohortHash())||bucket!=decision.bucket()
                    ||decision.baselineVersionNo()!=policy.baselineVersionNo()||decision.candidateVersionNo()!=policy.candidateVersionNo()
                    ||decision.candidateBasisPoints()!=policy.candidateBasisPoints()||decision.state()!=policy.state()
                    ||decision.side()!=(candidate?PipelineRolloutPolicy.Side.CANDIDATE:PipelineRolloutPolicy.Side.BASELINE)
                    ||decision.selectedVersionNo()!=version||!Objects.equals(decision.selectedReleaseHash(),releaseHash)
                    ||decision.comparison()==null||!Objects.equals(decision.comparison().selection(),policy.variable()))throw new IllegalArgumentException();
            var versionNode=prepared==null?null:prepared.get("versionNo");
            if(prepared==null||!prepared.isObject()||!pipelineId.equals(prepared.path("pipelineId").asText())
                    ||versionNode==null||!versionNode.isIntegralNumber()||!versionNode.canConvertToInt()||versionNode.intValue()!=version
                    ||prepared.hasNonNull("draftRevision")||prepared.path("trial").asBoolean(false)
                    ||!Objects.equals(prepared.path("contentHash").asText(),releaseHash)||!prepared.path("release").isObject()
                    ||!Objects.equals(PipelineAssetService.hash(prepared.path("release").toString()),releaseHash))throw new IllegalArgumentException();
        }catch(Exception invalid){throw IqcException.invalidState("灰度任务分配证据无效");}
    }
    public static PipelineRolloutTaskEvidence read(JsonNode envelope,ObjectMapper json){
        try{
            var body=envelope==null?null:envelope.get("evidence");
            if(body==null||!body.isObject()||!Objects.equals(envelope.path("evidenceHash").asText(),PipelineAssetService.hash(body.toString())))throw new IllegalArgumentException();
            return json.treeToValue(body,PipelineRolloutTaskEvidence.class);
        }catch(Exception invalid){throw IqcException.invalidState("灰度任务冻结证据无效");}
    }
}
