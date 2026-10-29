package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.util.List;

/** Versioned request identity: old snapshots retain their original digest, new publications preserve decimal precision. */
public final class StructuredToolInputHash {
    public static final String EXACT="EXACT_JSON_V1";
    public static final String LEGACY="LEGACY_JSON_V1";
    private StructuredToolInputHash() { }
    public static void validate(JsonNode config){
        var algorithm=config.get("inputHashAlgorithm");
        if(algorithm!=null && (!algorithm.isTextual() || !List.of(EXACT,LEGACY).contains(algorithm.textValue())))
            throw new IllegalArgumentException("Unsupported structured tool input hash algorithm");
    }
    static String hash(ObjectMapper json,JsonNode config,JsonNode request,JsonNode messages) throws Exception {
        validate(config);String value=json.writeValueAsString(List.of(request,messages));
        return EXACT.equals(config.path("inputHashAlgorithm").asText())?SelectorAssetService.hash(value):PipelineAssetService.hash(value);
    }
}
