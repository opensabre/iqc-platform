package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.BusinessReviewSampleService;
import io.github.opensabre.iqc.quality.model.QualitySample;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Provenance for an unlabelled mining candidate, never machine-generated ground truth. */
public record MinedCandidateLineage(String schemaVersion,String sourceResultId,String taskId,String executionId,
        String conversationId,String owner,String ownerGroupId,String sourceHash,String inputHash,String executionInputHash,
        String miningPolicyHash,String miningStrategy,int miningVersion,List<String> reasonCodes,String requestFingerprint) {
    public static final String SCHEMA="iqc-ai-mined-candidate-v1";
    public static final String SAMPLE_TYPE="UNCLASSIFIED";
    public MinedCandidateLineage {
        if(!SCHEMA.equals(schemaVersion))throw new IllegalArgumentException("Unsupported mined candidate lineage");
        for(String id:List.of(sourceResultId,taskId,executionId,conversationId))
            if(!id.matches("[A-Za-z0-9:_-]{1,64}"))throw new IllegalArgumentException("Mined candidate requires exact source identities");
        if(owner==null||owner.isBlank()||owner.length()>128||ownerGroupId!=null&&(ownerGroupId.isBlank()||ownerGroupId.length()>128))
            throw new IllegalArgumentException("Mined candidate requires bounded ownership");
        for(String hash:List.of(sourceHash,inputHash,executionInputHash,miningPolicyHash,requestFingerprint))
            if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Mined candidate requires source digests");
        if(miningStrategy==null||!miningStrategy.matches("[A-Z][A-Z0-9_]{1,63}")||miningVersion<1||miningVersion>1000)
            throw new IllegalArgumentException("Mined candidate strategy identity required");
        reasonCodes=List.copyOf(reasonCodes);
        if(reasonCodes.isEmpty()||reasonCodes.size()>20||reasonCodes.stream().distinct().count()!=reasonCodes.size()
                ||reasonCodes.stream().anyMatch(reason->reason==null||!reason.matches("[A-Z][A-Z0-9_]{1,63}")))
            throw new IllegalArgumentException("Mined candidate requires distinct bounded reasons");
    }
    /** Verify the immutable stored source, without recapturing archive data or approving its model output. */
    public static MinedCandidateLineage validate(QualitySample sample,ObjectMapper json){
        try{
            if(sample.getExpectedJson()==null||sample.getContentSnapshot()==null
                    ||sample.getExpectedJson().getBytes(StandardCharsets.UTF_8).length>4*1024*1024
                    ||sample.getContentSnapshot().getBytes(StandardCharsets.UTF_8).length>4*1024*1024)
                throw new IllegalArgumentException();
            var mapper=json.copy().disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
            var reader=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            JsonNode body=reader.readTree(sample.getExpectedJson());
            if(!body.isObject()||body.size()!=15)throw new IllegalArgumentException();
            for(String field:List.of("schemaVersion","sourceResultId","taskId","executionId","conversationId","owner",
                    "sourceHash","inputHash","executionInputHash","miningPolicyHash","miningStrategy","requestFingerprint"))
                if(!body.path(field).isTextual())throw new IllegalArgumentException();
            if(!body.path("reasonCodes").isArray())throw new IllegalArgumentException();
            for(var reason:body.path("reasonCodes"))if(!reason.isTextual())throw new IllegalArgumentException();
            if(!body.path("miningVersion").isIntegralNumber()||!body.path("miningVersion").canConvertToInt()
                    ||!body.has("ownerGroupId")||!(body.path("ownerGroupId").isNull()||body.path("ownerGroupId").isTextual()))
                throw new IllegalArgumentException();
            MinedCandidateLineage source=reader.forType(MinedCandidateLineage.class).readValue(body.toString());
            JsonNode input=reader.readTree(sample.getContentSnapshot());
            if(!SAMPLE_TYPE.equals(sample.getSampleType())||sample.getMessageId()!=null
                    ||!Objects.equals(sample.getSourceResultId(),source.sourceResultId())
                    ||!Objects.equals(sample.getConversationId(),source.conversationId())||!Objects.equals(sample.getCreatedBy(),source.owner())
                    ||sample.getOwnerGroupId()!=null&&!Objects.equals(sample.getOwnerGroupId(),source.ownerGroupId())
                    ||!BusinessReviewSampleService.EXACT_INPUT_SCHEMA.equals(input.path("schemaVersion").asText())
                    ||!source.conversationId().equals(input.path("conversationId").asText())
                    ||!source.inputHash().equals(BusinessReviewSampleService.candidateInputHash(input)))throw new IllegalArgumentException();
            return source;
        }catch(Exception invalid){throw IqcException.invalidState("自动候选的冻结输入、归属或待标注来源协议无效");}
    }
}
