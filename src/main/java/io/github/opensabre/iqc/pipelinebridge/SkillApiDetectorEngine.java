package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Executes a frozen business Skill through one explicitly approved read-only MCP tool. */
public final class SkillApiDetectorEngine implements EngineAdapter {
    public static final String VERSION="skill-api-v1";
    private final ObjectMapper json;
    private final SnapshotMcpToolProvider tools;
    public SkillApiDetectorEngine(ObjectMapper json,SnapshotMcpToolProvider tools){this.json=Objects.requireNonNull(json);this.tools=Objects.requireNonNull(tools);}
    @Override public String type(){return "SKILL_API";}
    /** Validates only frozen metadata; publication never invokes a remote tool. */
    public static void validateConfiguration(String version,JsonNode config) {
        if(!VERSION.equals(version) || config==null || !config.isObject())throw new IllegalArgumentException("Frozen Skill/API configuration required");
        for(String field:List.of("skillSnapshot","mcpSnapshot","toolInputSchema","toolOutputSchema","findingDefinition"))
            if(!config.path(field).isObject())throw new IllegalArgumentException("Missing Skill/API frozen contract: "+field);
        StructuredToolInputHash.validate(config);
        var skill=config.path("skillSnapshot");
        for(String field:List.of("id","code","instructions","inputSchemaJson","outputSchemaJson"))
            if(!skill.path(field).isTextual() || skill.path(field).asText().isBlank())throw new IllegalArgumentException("Skill requires versioned instructions and schemas");
        if(!skill.path("versionNo").isIntegralNumber() || !skill.path("versionNo").canConvertToInt() || skill.path("versionNo").intValue()<1)
            throw new IllegalArgumentException("Skill version required");
        if(!config.path("toolName").isTextual() || config.path("toolName").asText().isBlank())throw new IllegalArgumentException("Frozen tool name required");
        ValueSchema.validateSchema(config.path("toolInputSchema"));ValueSchema.validateSchema(config.path("toolOutputSchema"));
    }
    @Override public NodeResult execute(NodeContext context) throws Exception {
        var config=context.node().config();validateConfiguration(context.node().version(),config);
        var skill=config.path("skillSnapshot");var input=context.input();
        var inputSchema=json.readTree(skill.path("inputSchemaJson").asText());
        var outputSchema=json.readTree(skill.path("outputSchemaJson").asText());
        ValueSchema.checkData(inputSchema,input);ValueSchema.validateSchema(outputSchema);
        var manifest=context.variables().get("__messages");
        if(manifest==null || !manifest.isArray() || manifest.size()>10000 || input.has("messages") && !input.get("messages").equals(manifest))
            throw new IllegalArgumentException("Trusted processed conversation manifest required");
        var task=context.variables().get("__task");
        if(task==null || !task.path("currentExecutionId").isTextual() || task.path("currentExecutionId").asText().isBlank())
            throw new IllegalArgumentException("Trusted Skill execution identity required");
        var request=json.createObjectNode().put("schemaVersion","iqc-skill-request-v1");
        request.set("skill",skill);request.set("input",input);
        String hash=StructuredToolInputHash.hash(json,config,request,manifest);
        request.put("inputHash",hash);
        JsonNode output=StructuredToolTransport.invoke(json,tools,config,request);
        ValueSchema.checkData(config.path("toolOutputSchema"),output);ValueSchema.checkData(outputSchema,output);
        if(!"iqc-skill-response-v1".equals(output.path("schemaVersion").asText())
                || !hash.equals(output.path("inputHash").asText())
                || !skill.path("id").asText().equals(output.path("skillId").asText())
                || !output.path("skillVersion").isIntegralNumber() || !output.path("skillVersion").canConvertToInt()
                || skill.path("versionNo").intValue()!=output.path("skillVersion").intValue())
            throw new IllegalArgumentException("Skill output identity differs from frozen request");
        return StructuredToolFindingCodec.decode(json,context,output,
                task.path("currentExecutionId").asText(),hash,type(),VERSION);
    }
}
