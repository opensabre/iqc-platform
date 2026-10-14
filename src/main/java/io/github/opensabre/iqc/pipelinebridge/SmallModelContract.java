package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.finding.FindingDefinition;
import io.github.opensabre.iqc.pipeline.ValueSchema;
import java.util.Set;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;

/** Publication contract for dedicated inference, bound to original model and read-only MCP snapshots. */
public final class SmallModelContract {
    public static final String VERSION="small-model-v1";
    public enum Operation {CLASSIFICATION,NER,EMOTION,INTENT,SEMANTIC_MATCHING}
    private SmallModelContract() { }
    /** Rejects missing identities, unsupported operations or incompatible semantic outputs before publication. */
    public static void validateConfiguration(String version,JsonNode config,FindingDefinition definition) {
        if(!VERSION.equals(version)||config==null||!config.isObject()||definition==null)
            throw new IllegalArgumentException("Frozen small model contract required");
        if(!config.path("operation").isTextual())throw new IllegalArgumentException("Dedicated inference operation required");
        var operation=Operation.valueOf(config.path("operation").asText());
        var model=config.path("modelSnapshot");
        if(!model.isObject())throw new IllegalArgumentException("Frozen model asset required");
        for(String key:Set.of("id","modelName","provider","endpoint","deploymentRef"))
            if(!model.path(key).isTextual()||model.path(key).asText().isBlank()||model.path(key).asText().length()>2048)
                throw new IllegalArgumentException("Frozen model identity and deployment required");
        if(!model.path("versionNo").isIntegralNumber()||!model.path("versionNo").canConvertToInt()||model.path("versionNo").intValue()<1)
            throw new IllegalArgumentException("Exact model version required");
        if(!model.path("id").asText().matches("[A-Za-z0-9:_-]{1,64}"))
            throw new IllegalArgumentException("Exact model asset identity required");
        // Reuse the admission gateway's endpoint and deployment grammar at publication time.
        new GatewayCallService.ModelIdentity(model.path("provider").asText(), model.path("endpoint").asText(),
                model.path("modelName").asText(), null, null,
                new GatewayCallService.DeploymentDeclaration(null, model.path("deploymentRef").asText()));
        var permitted=switch(operation){
            case CLASSIFICATION -> Set.of(Finding.ValueType.BOOLEAN,Finding.ValueType.STRING,Finding.ValueType.ENUM);
            case EMOTION,INTENT -> Set.of(Finding.ValueType.STRING,Finding.ValueType.ENUM);
            case NER -> Set.of(Finding.ValueType.OBJECT);
            case SEMANTIC_MATCHING -> Set.of(Finding.ValueType.NUMBER);
        };
        if(!permitted.contains(definition.valueType()))throw new IllegalArgumentException("Finding type differs from inference operation");
        if(!config.path("mcpSnapshot").isObject()||!config.path("toolName").isTextual()||config.path("toolName").asText().isBlank())
            throw new IllegalArgumentException("Frozen read-only inference tool required");
        var mcp=config.path("mcpSnapshot");
        if(!mcp.path("id").isTextual()||!mcp.path("id").asText().matches("[A-Za-z0-9_-]{1,128}")
                ||!mcp.path("versionNo").isIntegralNumber()||!mcp.path("versionNo").canConvertToInt()
                ||mcp.path("versionNo").intValue()<1
                ||!config.path("toolName").asText().matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("Exact inference tool identity and version required");
        for(String key:Set.of("inputSchema","outputSchema","toolInputSchema","toolOutputSchema")) {
            if(!config.path(key).isObject())throw new IllegalArgumentException("Explicit inference schemas required");
            ValueSchema.validateSchema(config.get(key));
        }
    }
}
