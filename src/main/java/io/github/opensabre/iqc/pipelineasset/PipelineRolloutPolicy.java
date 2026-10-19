package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic preview only. Authorization, durable policy revision and task freezing belong to the owning workflow. */
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=PipelineRolloutPolicy.JsonCodec.class)
@tools.jackson.databind.annotation.JsonDeserialize(using=PipelineRolloutPolicy.HttpCodec.class)
public record PipelineRolloutPolicy(String schemaVersion,String pipelineId,Integer baselineVersionNo,Integer candidateVersionNo,
        Integer candidateBasisPoints,String cohortSalt,State state,BenchmarkVariableComparator.Selection variable) {
    public static final String SCHEMA="iqc-pipeline-rollout-v1";
    public enum State { ACTIVE, ROLLED_BACK }
    public enum Side { BASELINE, CANDIDATE }
    public record Decision(String schemaVersion,String pipelineId,String policyHash,String cohortHash,int bucket,
            int baselineVersionNo,int candidateVersionNo,int candidateBasisPoints,State state,Side side,
            int selectedVersionNo,String selectedReleaseHash,BenchmarkVariableComparator.Comparison comparison) { }
    public PipelineRolloutPolicy {
        if(!SCHEMA.equals(schemaVersion)||pipelineId==null||pipelineId.isBlank()||pipelineId.length()>64||pipelineId.codePoints().anyMatch(Character::isISOControl)
                ||baselineVersionNo==null||baselineVersionNo<1||candidateVersionNo==null||candidateVersionNo<1
                ||baselineVersionNo.equals(candidateVersionNo)||candidateBasisPoints==null||candidateBasisPoints<0||candidateBasisPoints>10000
                ||cohortSalt==null||!cohortSalt.matches("[A-Za-z0-9_-]{8,64}")||state==null||variable==null||variable.dimension()==null)
            throw IqcException.invalidArgument("灰度策略需指定两个不同发布版本、0至10000比例、有效分桶盐和单变量");
    }
    public Decision preview(String actorId,String requestKey,PipelineAssetService.Prepared baseline,
            PipelineAssetService.Prepared candidate,ObjectMapper json){
        if(actorId==null||!actorId.matches("[A-Za-z0-9:_-]{1,64}")||requestKey==null||!requestKey.matches("[A-Za-z0-9:_-]{1,128}")||json==null)
            throw IqcException.invalidArgument("灰度预览缺少稳定身份或有效请求键");
        requireVersion(baseline,baselineVersionNo);requireVersion(candidate,candidateVersionNo);
        var comparison=BenchmarkVariableComparator.compare(baseline.release(),candidate.release(),variable,json);
        String policyHash=io.github.opensabre.iqc.selector.SelectorAssetService.hash(json.valueToTree(this).toString());
        String cohortHash=cohortHash(actorId,requestKey);
        int bucket=new java.math.BigInteger(cohortHash,16).mod(java.math.BigInteger.valueOf(10000)).intValueExact();
        boolean useCandidate=state==State.ACTIVE && bucket<candidateBasisPoints;
        var selected=useCandidate?candidate:baseline;
        return new Decision(SCHEMA,pipelineId,policyHash,cohortHash,bucket,baselineVersionNo,candidateVersionNo,candidateBasisPoints,
                state,useCandidate?Side.CANDIDATE:Side.BASELINE,selected.versionNo(),selected.contentHash(),comparison);
    }
    public String cohortHash(String actorId,String requestKey){
        if(actorId==null||!actorId.matches("[A-Za-z0-9:_-]{1,64}")||requestKey==null||!requestKey.matches("[A-Za-z0-9:_-]{1,128}"))
            throw IqcException.invalidArgument("灰度分桶缺少稳定身份或有效请求键");
        return digest("iqc-pipeline-rollout-cohort-v1",pipelineId,actorId,requestKey,cohortSalt);
    }
    private void requireVersion(PipelineAssetService.Prepared source,int expected){
        if(source==null||!pipelineId.equals(source.pipelineId())||source.versionNo()==null||source.versionNo()!=expected
                ||source.draftRevision()!=null||source.release()==null||source.contentHash()==null||!source.contentHash().matches("[a-f0-9]{64}"))
            throw IqcException.invalidArgument("灰度预览必须使用同一流水线的精确已发布版本");
    }
    private static String digest(String... values){
        try{var digest=MessageDigest.getInstance("SHA-256");
            for(String value:values){var bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length+":").getBytes(StandardCharsets.UTF_8));digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    private static void fields(com.fasterxml.jackson.databind.JsonNode node,java.util.Set<String> allowed){
        if(node==null||!node.isObject())throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException();});
    }
    private static String text(com.fasterxml.jackson.databind.JsonNode node,String field){
        var value=node.get(field);if(value==null||!value.isTextual())throw new IllegalArgumentException();return value.textValue();
    }
    private static int integer(com.fasterxml.jackson.databind.JsonNode value){
        if(value==null||!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException();return value.intValue();
    }
    private static PipelineRolloutPolicy fromTree(com.fasterxml.jackson.databind.JsonNode node){
        fields(node,java.util.Set.of("schemaVersion","pipelineId","baselineVersionNo","candidateVersionNo","candidateBasisPoints","cohortSalt","state","variable"));
        var variable=node.get("variable");fields(variable,java.util.Set.of("dimension","nodeId","slot"));
        var nodeId=variable.get("nodeId");String id=nodeId==null||nodeId.isNull()?null:text(variable,"nodeId");
        if(id!=null&&(id.length()>64||id.codePoints().anyMatch(Character::isISOControl)))throw new IllegalArgumentException();
        var slot=variable.get("slot");Integer position=slot==null||slot.isNull()?null:integer(slot);
        return new PipelineRolloutPolicy(text(node,"schemaVersion"),text(node,"pipelineId"),integer(node.get("baselineVersionNo")),
                integer(node.get("candidateVersionNo")),integer(node.get("candidateBasisPoints")),text(node,"cohortSalt"),
                State.valueOf(text(node,"state")),new BenchmarkVariableComparator.Selection(
                    BenchmarkVariableComparator.Dimension.valueOf(text(variable,"dimension")),id,position));
    }
    public static final class JsonCodec extends com.fasterxml.jackson.databind.JsonDeserializer<PipelineRolloutPolicy>{
        @Override public PipelineRolloutPolicy deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            var tree=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer().deserialize(parser,context);
            try{return fromTree(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(PipelineRolloutPolicy.class,"灰度策略字段或精确整数无效");}
        }
    }
    public static final class HttpCodec extends tools.jackson.databind.ValueDeserializer<PipelineRolloutPolicy>{
        @Override public PipelineRolloutPolicy deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context){
            var tree=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer().deserialize(parser,context);
            try{return fromTree(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(PipelineRolloutPolicy.class,"灰度策略字段或精确整数无效");}
        }
    }

}
