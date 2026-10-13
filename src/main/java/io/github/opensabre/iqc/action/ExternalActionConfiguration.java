package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Frozen external arguments reference an exact deployment-owned destination, without URLs or credentials. */
public record ExternalActionConfiguration(String destinationId,int destinationVersion,JsonNode payload) {
    public ExternalActionConfiguration {
        if(destinationId==null || !destinationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || destinationVersion<1)
            throw new IllegalArgumentException("External destination requires an exact id and positive version");
        if(payload==null || !payload.isObject() || payload.toString().getBytes(StandardCharsets.UTF_8).length>16384)
            throw new IllegalArgumentException("External payload requires an object bounded to 16KiB");
        validateDepth(payload,0);payload=payload.deepCopy();
    }
    @Override public JsonNode payload(){return payload.deepCopy();}
    /** Pure publication-time validation; actual destination availability is checked separately. */
    public static ExternalActionConfiguration parse(JsonNode config){
        if(config==null || !config.isObject())throw new IllegalArgumentException("External action configuration requires an object");
        config.fieldNames().forEachRemaining(field->{
            if(!Set.of("destinationId","destinationVersion","payload").contains(field))
                throw new IllegalArgumentException("Unsupported external action configuration field");
        });
        var id=config.path("destinationId");var version=config.path("destinationVersion");
        if(!id.isTextual() || !version.isIntegralNumber() || !version.canConvertToInt())
            throw new IllegalArgumentException("External destination version must be an exact integer");
        return new ExternalActionConfiguration(id.textValue(),version.intValue(),config.get("payload"));
    }
    private static void validateDepth(JsonNode value,int depth){
        if(depth>16)throw new IllegalArgumentException("External payload nesting exceeds 16 levels");
        if(value.isContainerNode())value.forEach(child->validateDepth(child,depth+1));
    }
}
