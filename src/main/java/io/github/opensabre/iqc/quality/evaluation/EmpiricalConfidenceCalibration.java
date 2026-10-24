package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** A reproducible candidate derived from original evaluation evidence, not a publish permission or quality guarantee. */
public final class EmpiricalConfidenceCalibration {
    private EmpiricalConfidenceCalibration(){ }
    public static final String SCHEMA="iqc-confidence-calibration-candidate-v1";
    public static final String METHOD="EQUAL_WIDTH_10_MIN20";
    public static final String EVENT="TYPED_VALUE_CORRECTNESS_ON_KNOWN_GOLD";
    public static final int MINIMUM_BIN_SAMPLES=20;
    private static final ObjectMapper JSON=new ObjectMapper();
    /** Shared configurations may carry this opaque reference, never client-supplied fitted coefficients. */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ReferenceCodec.class)
    @tools.jackson.databind.annotation.JsonDeserialize(using=HttpReferenceCodec.class)
    public record Reference(String taskId,String executionId,String nodeId,String contentHash){
        public Reference {
            id(taskId,64);id(executionId,64);id(nodeId,256);
            if(contentHash==null||!contentHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("exact calibration content hash required");
        }
    }
    /** Both wire codecs require exactly four textual fields, without scalar coercion. */
    private static Reference reference(com.fasterxml.jackson.databind.JsonNode tree){
        var names=java.util.Set.of("taskId","executionId","nodeId","contentHash");
        if(tree==null||!tree.isObject()||tree.size()!=4
                ||tree.properties().stream().anyMatch(entry->!names.contains(entry.getKey())||!entry.getValue().isTextual()))
            throw new IllegalArgumentException("exact four-field calibration reference required");
        return new Reference(tree.path("taskId").textValue(),tree.path("executionId").textValue(),
                tree.path("nodeId").textValue(),tree.path("contentHash").textValue());
    }
    public static final class ReferenceCodec extends com.fasterxml.jackson.databind.JsonDeserializer<Reference>{
        @Override public Reference deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException{
            com.fasterxml.jackson.databind.JsonNode tree=new ObjectMapper().readerFor(com.fasterxml.jackson.databind.JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(parser);
            try{return reference(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(Reference.class,"Invalid exact calibration reference");}
        }
    }
    public static final class HttpReferenceCodec extends tools.jackson.databind.ValueDeserializer<Reference>{
        private static final tools.jackson.databind.json.JsonMapper HTTP=tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        @Override public Reference deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context){
            var tree=HTTP.readTree(parser);
            try{return reference(new ObjectMapper().readTree(tree.toString()));}
            catch(java.io.IOException|RuntimeException invalid){return context.reportInputMismatch(Reference.class,"Invalid exact calibration reference");}
        }
    }
    public record Source(String taskId,String executionId,String datasetHash,String pipelineHash,String evidenceHash,
                         String nodeId,String nodeHash,String findingCode,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String modelKey,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String deploymentRef){
        public Source(String taskId,String executionId,String datasetHash,String pipelineHash,String evidenceHash,String nodeId,String nodeHash,String findingCode){
            this(taskId,executionId,datasetHash,pipelineHash,evidenceHash,nodeId,nodeHash,findingCode,null,null);
        }
        public Source {
            id(taskId,64);id(executionId,64);id(nodeId,256);
            if((modelKey==null)!=(deploymentRef==null)||modelKey!=null&&(!modelKey.matches("[a-f0-9]{64}")
                    ||!deploymentRef.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}")))
                throw new IllegalArgumentException("calibration requires a complete model deployment identity");
            for(String hash:List.of(datasetHash,pipelineHash,evidenceHash,nodeHash))
                if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("calibration source hash required");
            if(findingCode==null||!findingCode.matches("[A-Z][A-Z0-9_]{1,63}"))throw new IllegalArgumentException("calibration finding code required");
        }
    }
    public record Bin(int index,long samples,long correct,BigDecimal calibratedProbability){
        public Bin {
            if(index<0||index>9||samples<0||samples>1000||correct<0||correct>samples)
                throw new IllegalArgumentException("invalid calibration bin counts");
            BigDecimal expected=samples<MINIMUM_BIN_SAMPLES?null:BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(samples),6,RoundingMode.HALF_UP);
            if(expected==null?calibratedProbability!=null:calibratedProbability==null||expected.compareTo(calibratedProbability)!=0)
                throw new IllegalArgumentException("calibration probability must follow exact bin counts");
        }
    }
    public record Artifact(String schemaVersion,String method,String event,Source source,long eligibleKnownGold,long scored,
                           int minimumBinSamples,List<Bin> bins,String contentHash){
        public Artifact {
            bins=List.copyOf(bins);
            if(!SCHEMA.equals(schemaVersion)||!METHOD.equals(method)||!EVENT.equals(event)||source==null
                    ||eligibleKnownGold<0||eligibleKnownGold>1000||scored<1||scored>eligibleKnownGold
                    ||minimumBinSamples!=MINIMUM_BIN_SAMPLES||bins.size()!=10)
                throw new IllegalArgumentException("invalid calibration candidate protocol");
            for(int index=0;index<10;index++)if(bins.get(index).index()!=index)throw new IllegalArgumentException("calibration bins must be ordered and complete");
            if(bins.stream().mapToLong(Bin::samples).sum()!=scored||!hash(source,eligibleKnownGold,scored,bins).equals(contentHash))
                throw new IllegalArgumentException("calibration candidate counts or evidence hash mismatch");
        }
        /** Unsupported score regions stay unknown; never manufacture a probability from an empty or small bin. */
        public BigDecimal apply(BigDecimal rawConfidence){
            if(rawConfidence==null)return null;
            if(rawConfidence.signum()<0||rawConfidence.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("confidence outside 0..1");
            return bins.get(Math.min(9,rawConfidence.multiply(BigDecimal.TEN).intValue())).calibratedProbability();
        }
    }
    static Artifact fit(Source source,long eligible,long scored,long[] counts,long[] correct){
        var bins=new java.util.ArrayList<Bin>();
        for(int index=0;index<10;index++)bins.add(new Bin(index,counts[index],correct[index],counts[index]<MINIMUM_BIN_SAMPLES?null:
                BigDecimal.valueOf(correct[index]).divide(BigDecimal.valueOf(counts[index]),6,RoundingMode.HALF_UP)));
        return new Artifact(SCHEMA,METHOD,EVENT,source,eligible,scored,MINIMUM_BIN_SAMPLES,bins,hash(source,eligible,scored,bins));
    }
    private static String hash(Source source,long eligible,long scored,List<Bin> bins){
        var body=JSON.createObjectNode().put("schemaVersion",SCHEMA).put("method",METHOD).put("event",EVENT)
                .put("eligibleKnownGold",eligible).put("scored",scored).put("minimumBinSamples",MINIMUM_BIN_SAMPLES);
        body.set("source",JSON.valueToTree(source));body.set("bins",JSON.valueToTree(bins));
        return SelectorAssetService.hash(body.toString());
    }
    private static void id(String value,int length){
        if(value==null||value.isBlank()||value.length()>length||!value.matches("[A-Za-z0-9:_-]+"))throw new IllegalArgumentException("calibration source identity required");
    }
}
