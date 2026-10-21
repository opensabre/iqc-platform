package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/** Frozen exact implementation identity and parameters; no executable client input. */
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=FusionExtensionBinding.Codec.class)
@tools.jackson.databind.annotation.JsonDeserialize(using=FusionExtensionBinding.HttpCodec.class)
public record FusionExtensionBinding(String strategyId,int version,String implementationHash,Map<String,String> parameters) {
    public FusionExtensionBinding {
        identity(strategyId);
        if(version<1||implementationHash==null||!implementationHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Custom fusion requires an exact version and implementation hash");
        parameters=parameters(parameters);
    }
    static void identity(String value){
        if(value==null||!value.matches("[A-Z][A-Z0-9_]{1,63}"))throw new IllegalArgumentException("Invalid fusion extension identity");
    }
    static Map<String,String> parameters(Map<String,String> source){
        var result=Map.copyOf(source==null?Map.of():source);
        if(result.size()>64||result.entrySet().stream().anyMatch(entry->!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                ||entry.getValue().length()>512||entry.getValue().codePoints().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid custom fusion parameters");
        return result;
    }
    private static FusionExtensionBinding decode(JsonNode tree){
        if(tree==null||!tree.isObject()||tree.size()!=4)throw new IllegalArgumentException();
        var keys=Set.of("strategyId","version","implementationHash","parameters");
        if(tree.properties().stream().anyMatch(entry->!keys.contains(entry.getKey()))
                ||!tree.path("strategyId").isTextual()||!tree.path("implementationHash").isTextual()
                ||!tree.path("version").isIntegralNumber()||!tree.path("version").canConvertToInt()
                ||!tree.path("parameters").isObject()||tree.path("parameters").size()>64)throw new IllegalArgumentException();
        var parameters=new java.util.LinkedHashMap<String,String>();
        tree.path("parameters").properties().forEach(entry->{if(!entry.getValue().isTextual())throw new IllegalArgumentException();parameters.put(entry.getKey(),entry.getValue().textValue());});
        return new FusionExtensionBinding(tree.path("strategyId").textValue(),tree.path("version").intValue(),tree.path("implementationHash").textValue(),parameters);
    }
    public static final class Codec extends com.fasterxml.jackson.databind.JsonDeserializer<FusionExtensionBinding>{
        @Override public FusionExtensionBinding deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException{
            var tree=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer().deserialize(parser,context);
            try{return decode(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(FusionExtensionBinding.class,"Invalid exact custom fusion binding");}
        }
    }
    public static final class HttpCodec extends tools.jackson.databind.ValueDeserializer<FusionExtensionBinding>{
        @Override public FusionExtensionBinding deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context){
            var tree=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer().deserialize(parser,context);
            try{return decode(tree);}catch(RuntimeException invalid){return context.reportInputMismatch(FusionExtensionBinding.class,"Invalid exact custom fusion binding");}
        }
    }
}
