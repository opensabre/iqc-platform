package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import java.util.*;

/** Numeric facts from the complete trusted transcript manifest; no model or business side effects. */
public final class TranscriptMetricEngine implements EngineAdapter {
    public static final String VERSION="transcript-timing-v1";
    private final ObjectMapper json;
    public TranscriptMetricEngine(ObjectMapper json){this.json=Objects.requireNonNull(json);}
    @Override public String type(){return "METRIC";}
    /** Shared publication/execution contract; frozen definitions are resolved by the owning asset service. */
    public static void validateConfiguration(String version,com.fasterxml.jackson.databind.JsonNode config,FindingDefinition definition) {
        if(!VERSION.equals(version))throw new IllegalArgumentException("Unsupported transcript metric version");
        if(config==null || !config.isObject())throw new IllegalArgumentException("Metric configuration required");
        var fields=config.fieldNames();
        while(fields.hasNext())if(!Set.of("metric","agentRole","customerRole","findingReference","findingDefinition").contains(fields.next()))
            throw new IllegalArgumentException("Unknown transcript metric configuration field");
        for(String key:List.of("metric","agentRole","customerRole"))
            if(!config.path(key).isTextual() || config.path(key).asText().isBlank() || config.path(key).asText().length()>64)
                throw new IllegalArgumentException("Transcript metric requires bounded explicit configuration");
        TranscriptTimingMetrics.Metric.valueOf(config.path("metric").asText());
        if(config.path("agentRole").asText().equals(config.path("customerRole").asText()))
            throw new IllegalArgumentException("Transcript participant roles must differ");
        if(definition==null || definition.valueType()!=Finding.ValueType.NUMBER
                || !definition.evidenceTypes().isEmpty() && !definition.evidenceTypes().contains(Finding.EvidenceType.METRIC))
            throw new IllegalArgumentException("Metric requires a numeric semantic definition permitting metric evidence");
    }
    @Override public NodeResult execute(NodeContext context) throws Exception {
        var config=context.node().config();
        var definition=json.treeToValue(config==null?null:config.path("findingDefinition"),FindingDefinition.class);
        validateConfiguration(context.node().version(),config,definition);
        var metric=TranscriptTimingMetrics.Metric.valueOf(config.path("metric").asText());
        var manifest=context.variables().get("__messages");
        if(manifest==null || !manifest.isArray() || manifest.size()>10000)
            throw new IllegalArgumentException("Complete trusted transcript manifest required");
        if(context.input().has("messages") && !manifest.equals(context.input().get("messages")))
            throw new IllegalArgumentException("Metric input cannot replace or subset the trusted transcript manifest");
        List<ConversationMessage> messages=json.convertValue(manifest,new TypeReference<>(){});
        var measured=TranscriptTimingMetrics.measure(metric,messages,config.path("agentRole").asText(),config.path("customerRole").asText());
        String hash=PipelineAssetService.hash(json.writeValueAsString(manifest));
        var task=context.variables().get("__task");
        if(task==null || !task.path("currentExecutionId").isTextual() || task.path("currentExecutionId").asText().isBlank())
            throw new IllegalArgumentException("Trusted metric execution identity required");
        String id=PipelineAssetService.hash(json.writeValueAsString(List.of(task.path("currentExecutionId").asText(),
                context.node().nodeId(),VERSION,metric.name(),context.attempt(),hash)));
        var evidence=new ArrayList<Finding.Evidence>();
        if(measured.state()==Finding.State.KNOWN) {
            var details=json.createObjectNode().put("schemaVersion","iqc-transcript-timing-evidence-v1")
                    .put("metric",metric.name()).put("unit",measured.unit()).put("inputHash",hash)
                    .put("observations",measured.observations());
            details.putArray("messageIds").addAll(messages.stream().map(message->json.getNodeFactory().textNode(message.getId())).toList());
            evidence.add(new Finding.Evidence(id+":metric",Finding.EvidenceType.METRIC,metric.name(),details));
        }
        var fact=new Finding(id,definition.code(),definition.category(),Finding.ValueType.NUMBER,
                measured.value()==null?null:json.getNodeFactory().numberNode(measured.value()),measured.state(),null,
                definition.defaultSeverity(),measured.reason(),evidence,
                new Finding.Source(context.node().nodeId(),type(),VERSION,context.attempt(),hash));
        definition.validate(fact);
        var variables=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        variables.put(context.node().nodeId()+"_state",json.valueToTree(fact.state()));
        if(fact.state()==Finding.State.KNOWN)variables.put(context.node().nodeId()+"_value",fact.value());
        return new NodeResult(NodeResult.Status.SUCCESS,List.of(fact),variables,Map.of("transcriptTiming",json.valueToTree(measured)),null);
    }
}
