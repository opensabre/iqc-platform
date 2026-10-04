package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;

/** Shared V2 detector bridge; trusted task/messages are injected by the task worker, not by model variables. */
public final class FrozenDetectorEngine implements EngineAdapter {
    private final String type;
    private final ObjectMapper json;
    private final ObjectProvider<InspectionExecutionService> executor;
    public FrozenDetectorEngine(String type,ObjectMapper json,ObjectProvider<InspectionExecutionService> executor) {
        this.type=type; this.json=json; this.executor=executor;
    }
    @Override public String type() { return type; }
    @Override public NodeResult execute(NodeContext context) throws Exception {
        var config=context.node().config(); var rule=config.path("ruleSnapshot");
        var task=json.treeToValue(context.variables().get("__task"),InspectionTask.class);
        List<ConversationMessage> sources=json.convertValue(context.variables().get("__messages"),new TypeReference<>() { });
        JsonNode requested=context.input().path("messages");
        List<ConversationMessage> inputs=requested.isMissingNode()?sources:json.convertValue(requested,new TypeReference<>() { });
        if(inputs.isEmpty()) return NodeResult.failure(NodeResult.Status.SKIPPED,"No conversation inputs");
        for(var input:inputs) {
            var source=sources.stream().filter(value->Objects.equals(value.getId(),input.getId())).findFirst().orElseThrow();
            if(input.getContent()==null || input.getContent().isBlank() || source.getContent()==null
                    || !source.getContent().contains(input.getContent()) || !Objects.equals(source.getConversationId(),input.getConversationId())
                    || !Objects.equals(source.getSpeakerRole(),input.getSpeakerRole())) throw new IllegalArgumentException("Mapped messages do not belong to this execution");
        }
        String scope=config.path("inputScope").asText("RULE".equals(type)?"MESSAGE":"CONVERSATION");
        if(!Set.of("MESSAGE","CONVERSATION").contains(scope)) throw new IllegalArgumentException("Unsupported detector input scope");
        String targetRole=rule.path("targetRole").asText("all");
        if(!targetRole.isBlank() && !"all".equalsIgnoreCase(targetRole)
                && inputs.stream().noneMatch(message->targetRole.equalsIgnoreCase(message.getSpeakerRole())))
            return NodeResult.failure(NodeResult.Status.SKIPPED,"Target participant not present");
        String phase=config.path("phase").asText("DIRECT");
        if(!Set.of("DIRECT","REVIEW","VERIFY").contains(phase)) throw new IllegalArgumentException("Unsupported detector phase");
        InspectionResult upstream=null;
        for(var dependency:context.node().dependsOn()) {
            var prior=context.upstream().get(dependency);
            if(prior!=null && !prior.findings().isEmpty()) {
                var fact=prior.findings().getFirst();
                if(fact.state()!=Finding.State.KNOWN || fact.valueType()!=Finding.ValueType.BOOLEAN) continue;
                upstream=new InspectionResult(); upstream.setResultStatus(fact.value().booleanValue()?"HIT":"NOT_HIT");
                upstream.setReason(fact.reason()); upstream.setRuleId(dependency);
                var evidence=json.createArrayNode();
                for(var quote:fact.evidence()) if(quote.type()==Finding.EvidenceType.CONVERSATION && quote.details()!=null)
                    evidence.addObject().put("messageId",quote.reference()).put("text",quote.details().path("text").asText());
                upstream.setEvidenceJson(evidence.toString()); break;
            }
        }
        if("VERIFY".equals(phase)) {
            if(upstream==null || !"HIT".equals(upstream.getResultStatus()))
                return NodeResult.failure(NodeResult.Status.ERROR,"Verification requires a known positive upstream observation");
            var slices=new ArrayList<ConversationMessage>();
            for(var quote:json.readTree(upstream.getEvidenceJson())) {
                var original=sources.stream().filter(message->quote.path("messageId").asText().equals(message.getId())).findFirst().orElse(null);
                String text=quote.path("text").asText();
                if(original==null || text.isBlank() || !original.getContent().contains(text))
                    return NodeResult.failure(NodeResult.Status.ERROR,"Verification citation does not belong to the supplied conversation");
                var copy=new ConversationMessage(); org.springframework.beans.BeanUtils.copyProperties(original,copy); copy.setContent(text); slices.add(copy);
            }
            if(slices.isEmpty()) return NodeResult.failure(NodeResult.Status.ERROR,"Verification has no quoted source slices");
            inputs=List.copyOf(slices); // Never fall back to scanning the complete conversation.
        }
        var outcome=executor.getObject().evaluateFrozenPipelineNode(task,rule,config.get("agentSnapshot"),inputs,upstream,
                phase,scope,context.node().nodeId(),context.attempt());
        if("ERROR".equals(outcome.getResultStatus())) return NodeResult.failure(NodeResult.Status.ERROR,"Detector failed");
        var citations=json.createArrayNode();
        if(outcome.getEvidenceJson()!=null) {
            var values=json.readTree(outcome.getEvidenceJson()); if(values!=null && values.isArray()) values.forEach(citations::add);
        }
        // Provider JSON has already been parsed by LlmQualityProvider; validate referenced quotes here.
        if("LLM".equals(type) && outcome.getFindingJson()!=null) {
            var observations=json.readTree(outcome.getFindingJson()).path("observations");
            for(var observation:observations) {
                var payload=observation.path("finding");
                var values=payload.path("evidence"); if(values.isArray()) values.forEach(citations::add);
            }
        }
        String inputHash=PipelineAssetService.hash(json.writeValueAsString(inputs));
        var source=new Finding.Source(context.node().nodeId(),type,context.node().version(),context.attempt(),inputHash);
        String identity=task.getCurrentExecutionId()+":"+inputs.getFirst().getConversationId()+":"+context.node().nodeId()+":"+context.attempt();
        String id=PipelineAssetService.hash(json.writeValueAsString(identity));
        var observed=LegacyFindingAdapter.adapt(id,rule,outcome.getResultStatus(),true,outcome.getReason(),citations,inputs,source);
        if(observed.state()==Finding.State.ERROR) return NodeResult.failure(NodeResult.Status.ERROR,"Invalid detector evidence");
        var definition=json.treeToValue(config.path("findingDefinition"),FindingDefinition.class);
        var evidence=new ArrayList<>(observed.evidence());
        // Deterministic absence/threshold results are supported by the frozen rule and complete supplied input manifest.
        if(!"LLM".equals(type) && observed.state()==Finding.State.KNOWN && evidence.isEmpty()) {
            var details=json.createObjectNode().put("ruleId",rule.path("id").asText()).put("ruleVersion",rule.path("versionNo").asInt())
                    .put("inputHash",inputHash).put("inputScope",scope).put("resultStatus",outcome.getResultStatus());
            evidence.add(new Finding.Evidence(id+":rule",Finding.EvidenceType.RULE_MATCH,rule.path("id").asText(),details));
        }
        var fact=new Finding(observed.id(),definition.code(),definition.category(),definition.valueType(),observed.value(),
                observed.state(),observed.confidence(),definition.defaultSeverity(),observed.reason(),evidence,source);
        try { definition.validate(fact); }
        catch(IllegalArgumentException ex) { return NodeResult.failure(NodeResult.Status.ERROR,"Finding violates its frozen semantic/evidence contract"); }
        var variables=new LinkedHashMap<String,JsonNode>();
        variables.put(context.node().nodeId()+"_state",json.valueToTree(fact.state()));
        if(fact.state()==Finding.State.KNOWN) variables.put(context.node().nodeId()+"_value",fact.value());
        return new NodeResult(NodeResult.Status.SUCCESS,List.of(fact),variables,Map.of(),null);
    }
}
