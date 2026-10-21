package io.github.opensabre.iqc.fusion;

import java.util.Map;
import java.util.Set;

/** Frozen selection policy. Higher priority wins; confidence values must already be calibrated. */
public record FusionPolicy(String id, int version, Method method, Map<String, Integer> nodePriority,
                           Map<String, String> overrideNodeByCode, String confidenceCalibrationId,
                           Set<String> calibratedNodeIds,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                           @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=WeightsCodec.class)
                           @tools.jackson.databind.annotation.JsonDeserialize(using=HttpWeightsCodec.class)
                           Map<String,String> nodeWeights,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                           FusionExtensionBinding custom) {
    public enum Method { PRIORITY, CONFIDENCE, VOTING, OVERRIDE, WEIGHTED, CUSTOM }
    public FusionPolicy(String id,int version,Method method,Map<String,Integer> nodePriority,
            Map<String,String> overrideNodeByCode,String confidenceCalibrationId,Set<String> calibratedNodeIds,Map<String,String> nodeWeights){
        this(id,version,method,nodePriority,overrideNodeByCode,confidenceCalibrationId,calibratedNodeIds,nodeWeights,null);
    }
    public FusionPolicy(String id,int version,Method method,Map<String,Integer> nodePriority,
            Map<String,String> overrideNodeByCode,String confidenceCalibrationId,Set<String> calibratedNodeIds){
        this(id,version,method,nodePriority,overrideNodeByCode,confidenceCalibrationId,calibratedNodeIds,null,null);
    }
    public FusionPolicy {
        if (id == null || id.isBlank() || version < 1 || method == null)
            throw new IllegalArgumentException("fusion policy requires id, positive version and method");
        if((method==Method.CUSTOM)!=(custom!=null))throw new IllegalArgumentException("Custom binding must be present only for CUSTOM fusion");
        nodePriority = Map.copyOf(nodePriority == null ? Map.of() : nodePriority);
        overrideNodeByCode = Map.copyOf(overrideNodeByCode == null ? Map.of() : overrideNodeByCode);
        calibratedNodeIds = Set.copyOf(calibratedNodeIds == null ? Set.of() : calibratedNodeIds);
        var normalizedWeights=new java.util.TreeMap<String,String>();
        if(nodeWeights!=null){
            if(nodeWeights.size()>200)throw new IllegalArgumentException("weighted fusion supports at most 200 nodes");
            nodeWeights.forEach((node,weight)->{
                if(node==null||node.isBlank()||node.length()>128||node.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("weighted fusion requires valid source node IDs");
                normalizedWeights.put(node,normalizeWeight(weight));
            });
        }
        nodeWeights=Map.copyOf(normalizedWeights);
        if(method==Method.WEIGHTED?nodeWeights.isEmpty():!nodeWeights.isEmpty())
            throw new IllegalArgumentException("node weights must be present only for weighted fusion");
        if (method == Method.CONFIDENCE && (confidenceCalibrationId == null || confidenceCalibrationId.isBlank()
                || calibratedNodeIds.isEmpty()))
            throw new IllegalArgumentException("confidence fusion requires calibration identity and calibrated nodes");
        if (nodePriority.keySet().stream().anyMatch(String::isBlank)
                || overrideNodeByCode.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue().isBlank())
                || calibratedNodeIds.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("fusion policy references cannot be blank");
    }
    private static String normalizeWeight(String value){
        if(value==null||value.length()>32||!value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,12})?"))
            throw new IllegalArgumentException("node weight must be a positive plain decimal JSON string");
        var decimal=new java.math.BigDecimal(value);
        if(decimal.signum()<=0||decimal.compareTo(java.math.BigDecimal.valueOf(1_000_000))>0)
            throw new IllegalArgumentException("node weight must be greater than zero and at most 1000000");
        return decimal.stripTrailingZeros().toPlainString();
    }
    private static Map<String,String> weights(com.fasterxml.jackson.databind.JsonNode tree){
        if(tree==null||!tree.isObject()||tree.size()>200)throw new IllegalArgumentException();
        var values=new java.util.LinkedHashMap<String,String>();
        tree.fields().forEachRemaining(entry->{
            if(!entry.getValue().isTextual())throw new IllegalArgumentException();
            values.put(entry.getKey(),entry.getValue().textValue());
        });
        return values;
    }
    public static final class WeightsCodec extends com.fasterxml.jackson.databind.JsonDeserializer<Map<String,String>>{
        @Override public Map<String,String> deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException{
            var tree=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer().deserialize(parser,context);
            try{return weights(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(Map.class,"节点权重须为十进制JSON字符串对象");}
        }
    }
    public static final class HttpWeightsCodec extends tools.jackson.databind.ValueDeserializer<Map<String,String>>{
        @Override public Map<String,String> deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context){
            var tree=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer().deserialize(parser,context);
            try{return weights(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(Map.class,"节点权重须为十进制JSON字符串对象");}
        }
    }

}
