package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.rule.dls.DlsEngine;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Source-bound session observations reuse the owning DLS language and never call models or business actions. */
public final class SessionAggregationEngine implements EngineAdapter {
    public static final String VERSION="dls-session-v1";
    public enum Aggregation { DLS_ENTRY_MATCH, MATCHED_MESSAGE_COUNT }
    private final ObjectMapper json;
    public SessionAggregationEngine(ObjectMapper json){this.json=Objects.requireNonNull(json);}
    @Override public String type(){return "SESSION";}
    public static DlsEngine.Compiled validateConfiguration(ObjectMapper json,String version,JsonNode config,FindingDefinition definition) {
        if(!VERSION.equals(version) || config==null || !config.isObject())throw new IllegalArgumentException("Invalid session version/configuration");
        for(var field:config.properties())if(!Set.of("aggregation","countDefinition","ruleReference","ruleSnapshot","findingReference","findingDefinition").contains(field.getKey()))
            throw new IllegalArgumentException("Unsupported session configuration field");
        if(!config.path("aggregation").isTextual())throw new IllegalArgumentException("Explicit session aggregation required");
        var aggregation=Aggregation.valueOf(config.path("aggregation").asText());
        var rule=config.path("ruleSnapshot");var ref=config.path("ruleReference");
        if(!rule.isObject() || !"DLS".equalsIgnoreCase(rule.path("ruleType").asText()) || !rule.path("expression").isTextual()
                || !ref.path("id").isTextual() || ref.path("id").asText().isBlank() || !ref.path("id").equals(rule.path("id"))
                || !ref.path("versionNo").isIntegralNumber() || !ref.path("versionNo").canConvertToInt() || ref.path("versionNo").intValue()<1
                || !rule.path("versionNo").isIntegralNumber() || !rule.path("versionNo").canConvertToInt()
                || ref.path("versionNo").intValue()!=rule.path("versionNo").intValue())
            throw new IllegalArgumentException("Session requires the exact frozen published DLS rule");
        var compiled=DlsEngine.compile(json,rule.path("expression").asText());
        var count=config.path("countDefinition");
        if(aggregation==Aggregation.MATCHED_MESSAGE_COUNT) {
            if(!count.isTextual() || count.asText().isBlank() || count.asText().length()>256 || !compiled.rulePatterns().containsKey(count.asText()))
                throw new IllegalArgumentException("Count requires an existing DLS RULE definition");
        } else if(!count.isMissingNode())throw new IllegalArgumentException("Entry aggregation cannot carry a count definition");
        var expected=aggregation==Aggregation.DLS_ENTRY_MATCH?Finding.ValueType.BOOLEAN:Finding.ValueType.NUMBER;
        if(definition==null || definition.valueType()!=expected
                || !definition.evidenceTypes().isEmpty() && !definition.evidenceTypes().contains(Finding.EvidenceType.RULE_MATCH))
            throw new IllegalArgumentException("Session finding type/evidence differs from aggregation");
        return compiled;
    }
    @Override public NodeResult execute(NodeContext context) throws Exception {
        var config=context.node().config();
        var definition=json.treeToValue(config==null?null:config.path("findingDefinition"),FindingDefinition.class);
        var compiled=validateConfiguration(json,context.node().version(),config,definition);
        var aggregation=Aggregation.valueOf(config.path("aggregation").asText());
        var manifest=context.variables().get("__messages");
        if(manifest==null || !manifest.isArray() || manifest.size()>10000)
            throw new IllegalArgumentException("Complete trusted session manifest required");
        if(context.input().has("messages") && !manifest.equals(context.input().get("messages")))
            throw new IllegalArgumentException("Session inputs cannot replace or subset the complete manifest");
        String sourceJson=json.writeValueAsString(manifest);
        if(sourceJson.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new IllegalArgumentException("Session input exceeds 16MiB");
        Set<String> ids=new HashSet<>();Set<Integer> sequences=new HashSet<>();String conversation=null;
        for(var message:manifest) {
            for(String field:List.of("id","conversationId","speakerRole","content"))
                if(!message.path(field).isTextual() || message.path(field).asText().isBlank())throw new IllegalArgumentException("Session message identity/content required");
            var sequence=message.path("sequenceNo");String current=message.path("conversationId").asText();
            if(!sequence.isIntegralNumber() || !sequence.canConvertToInt() || sequence.intValue()<0 || !sequences.add(sequence.intValue())
                    || !ids.add(message.path("id").asText()) || conversation!=null && !conversation.equals(current))
                throw new IllegalArgumentException("Session messages must belong to one conversation with unique identities and sequences");
            conversation=current;
        }
        List<ConversationMessage> messages=json.convertValue(manifest,new TypeReference<>(){});
        var task=context.variables().get("__task");
        if(task==null || !task.path("currentExecutionId").isTextual() || task.path("currentExecutionId").asText().isBlank())
            throw new IllegalArgumentException("Trusted session execution identity required");
        String hash=PipelineAssetService.hash(sourceJson);
        String id=PipelineAssetService.hash(json.writeValueAsString(List.of(task.path("currentExecutionId").asText(),context.node().nodeId(),VERSION,
                aggregation.name(),config.path("countDefinition").asText(""),context.attempt(),hash)));
        var evidence=new ArrayList<Finding.Evidence>();JsonNode value=null;var state=Finding.State.UNKNOWN;String reason="Complete session has no messages";
        String targetRole=config.path("ruleSnapshot").path("targetRole").asText("all");
        boolean applicable=targetRole.isBlank() || "all".equalsIgnoreCase(targetRole)
                || messages.stream().anyMatch(message->targetRole.equalsIgnoreCase(message.getSpeakerRole()));
        if(!messages.isEmpty() && !applicable)reason="Declared rule participant is absent from complete session";
        if(!messages.isEmpty() && applicable) {
            var result=DlsEngine.evaluateSession(compiled,messages,aggregation==Aggregation.MATCHED_MESSAGE_COUNT?config.path("countDefinition").asText():null);
            value=aggregation==Aggregation.DLS_ENTRY_MATCH?json.getNodeFactory().booleanNode(result.entry().hit()):json.getNodeFactory().numberNode(result.matchedMessageCount());
            state=Finding.State.KNOWN;reason=aggregation==Aggregation.DLS_ENTRY_MATCH?result.entry().reason():"Distinct messages matching the declared DLS subrule";
            var rule=config.path("ruleSnapshot");
            var details=json.createObjectNode().put("schemaVersion","iqc-session-aggregation-evidence-v1").put("aggregation",aggregation.name())
                    .put("inputHash",hash).put("ruleId",rule.path("id").asText()).put("ruleVersion",rule.path("versionNo").intValue())
                    .put("messageCount",messages.size());
            if(aggregation==Aggregation.MATCHED_MESSAGE_COUNT)details.put("countDefinition",config.path("countDefinition").asText()).put("unit","matched_messages");
            details.set("matches",json.valueToTree(aggregation==Aggregation.DLS_ENTRY_MATCH?result.entry().evidence():result.countedEvidence()));
            details.putArray("messageIds").addAll(messages.stream().map(message->json.getNodeFactory().textNode(message.getId())).toList());
            evidence.add(new Finding.Evidence(id+":session",Finding.EvidenceType.RULE_MATCH,rule.path("id").asText(),details));
        }
        var fact=new Finding(id,definition.code(),definition.category(),definition.valueType(),value,state,null,definition.defaultSeverity(),reason,evidence,
                new Finding.Source(context.node().nodeId(),type(),VERSION,context.attempt(),hash));
        definition.validate(fact);
        var variables=new LinkedHashMap<String,JsonNode>();variables.put(context.node().nodeId()+"_state",json.valueToTree(state));
        if(state==Finding.State.KNOWN)variables.put(context.node().nodeId()+"_value",value);
        return new NodeResult(NodeResult.Status.SUCCESS,List.of(fact),variables,Map.of(),null);
    }
}
