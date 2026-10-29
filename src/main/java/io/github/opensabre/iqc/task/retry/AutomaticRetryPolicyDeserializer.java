package io.github.opensabre.iqc.task.retry;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
public final class AutomaticRetryPolicyDeserializer extends JsonDeserializer<AutomaticRetryPolicy> {
    @Override public AutomaticRetryPolicy deserialize(JsonParser parser,DeserializationContext context)throws IOException{
        JsonNode node=parser.getCodec().readTree(parser);
        if(!node.isObject())return (AutomaticRetryPolicy)context.handleUnexpectedToken(AutomaticRetryPolicy.class,parser);
        var fields=node.fieldNames();var allowed=Set.of("schemaVersion","maximumAttempts","initialDelaySeconds","multiplier","maximumDelaySeconds","effectiveUntil");
        while(fields.hasNext())if(!allowed.contains(fields.next()))throw JsonMappingException.from(parser,"未知自动重试策略字段");
        var schema=node.get("schemaVersion");if(schema==null || !schema.isTextual())throw JsonMappingException.from(parser,"缺少重试策略协议");
        Instant until=null;var effective=node.get("effectiveUntil");
        if(effective!=null && !effective.isNull()){
            if(!effective.isTextual())throw JsonMappingException.from(parser,"重试有效期必须为ISO Instant");
            try{until=Instant.parse(effective.textValue());}catch(RuntimeException error){throw JsonMappingException.from(parser,"重试有效期无效");}}
        return new AutomaticRetryPolicy(schema.textValue(),integer(node,"maximumAttempts",parser),integer(node,"initialDelaySeconds",parser),
                integer(node,"multiplier",parser),integer(node,"maximumDelaySeconds",parser),until);
    }
    private int integer(JsonNode node,String name,JsonParser parser)throws JsonMappingException{
        var value=node.get(name);if(value==null || !value.isIntegralNumber() || !value.canConvertToInt())throw JsonMappingException.from(parser,"重试策略需要精确整数: "+name);
        return value.intValue();
    }
}
