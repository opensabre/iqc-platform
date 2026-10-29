package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import java.util.*;

/** Shared canonical output validation for approved structured inference tools. */
final class StructuredToolFindingCodec {
    private StructuredToolFindingCodec() { }
    static NodeResult decode(ObjectMapper json, NodeContext context, JsonNode output,
                             String executionId, String hash, String type, String version) throws Exception {
        if(!output.path("state").isTextual() || !output.path("reason").isTextual() || output.path("reason").asText().length()>2000)
            throw new IllegalArgumentException("Structured tool output requires state and bounded reason");
        var state=Finding.State.valueOf(output.path("state").asText());
        var definition=json.treeToValue(context.node().config().path("findingDefinition"),FindingDefinition.class);
        String id=PipelineAssetService.hash(json.writeValueAsString(List.of(executionId,context.node().nodeId(),version,context.attempt(),hash)));
        var evidence=new ArrayList<Finding.Evidence>();
        if(output.has("evidence")) {
            if(!output.path("evidence").isArray() || output.path("evidence").size()>100)throw new IllegalArgumentException("Invalid structured tool evidence");
            for(var item:output.path("evidence")) {
                if(!item.path("reference").isTextual() || item.path("reference").asText().isBlank() || item.path("reference").asText().length()>512)
                    throw new IllegalArgumentException("External tool evidence reference required");
                evidence.add(new Finding.Evidence(id+":"+evidence.size(),Finding.EvidenceType.EXTERNAL_RESULT,item.path("reference").asText(),item.get("details")));
            }
        }
        if(output.hasNonNull("confidence") && !output.path("confidence").isNumber())throw new IllegalArgumentException("Structured tool confidence must be numeric");
        var fact=new Finding(id,definition.code(),definition.category(),definition.valueType(),output.get("value"),state,
                output.hasNonNull("confidence")?output.get("confidence").decimalValue():null,definition.defaultSeverity(),output.path("reason").asText(),evidence,
                new Finding.Source(context.node().nodeId(),type,version,context.attempt(),hash));
        definition.validate(fact);
        var variables=new LinkedHashMap<String,JsonNode>();variables.put(context.node().nodeId()+"_state",json.valueToTree(state));
        if(state==Finding.State.KNOWN)variables.put(context.node().nodeId()+"_value",fact.value());
        return new NodeResult(NodeResult.Status.SUCCESS,List.of(fact),variables,Map.of(),null);
    }
}
