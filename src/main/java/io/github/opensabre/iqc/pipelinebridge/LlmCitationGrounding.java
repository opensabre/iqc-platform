package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.LegacyFindingAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Observes raw, explicitly addressed quote claims before filtering. Never changes a detector verdict. */
public final class LlmCitationGrounding {
    private LlmCitationGrounding() { }
    public record Diagnostic(String schemaVersion,String scope,String state,long outputs,long recognizedOutputs,
                             long unmeasuredOutputs,long claimedQuotes,long invalidQuotes,long malformedContainers) { }
    public static Diagnostic inspect(JsonNode root,List<ConversationMessage> inputs){
        var counter=new Counter(inputs);
        String schema=root==null?"":root.path("schemaVersion").asText();
        if(("iqc-stage-observations-v1".equals(schema)||"iqc-rule-observations-v2".equals(schema))
                &&root.path("observations").isArray()&&!root.path("observations").isEmpty()){
            for(var observation:root.path("observations"))counter.output(observation.path("finding"));
        }else counter.output(root);
        String state=counter.recognized==0?"UNMEASURED":counter.unmeasured>0||counter.malformed>0?"PARTIAL":"MEASURED";
        return new Diagnostic("iqc-llm-citation-grounding-v1","RAW_EXPLICIT_CONVERSATION_QUOTE_CLAIMS",state,
                counter.outputs,counter.recognized,counter.unmeasured,counter.claimed,counter.invalid,counter.malformed);
    }
    private static final class Counter {
        final Map<String,ConversationMessage> messages=new LinkedHashMap<>();
        long outputs,recognized,unmeasured,claimed,invalid,malformed;
        Counter(List<ConversationMessage> inputs){Objects.requireNonNull(inputs).forEach(message->messages.putIfAbsent(message.getId(),message));}
        void output(JsonNode value){
            outputs++;
            if(value==null||!value.isObject()){unmeasured++;return;}
            String schema=value.path("schemaVersion").asText();
            if("iqc-label-facts-v2".equals(schema)){
                recognized++;
                if(!value.path("facts").isArray()){malformed++;return;}
                for(var fact:value.path("facts"))quotes(fact.path("evidence"),fact.path("subjectRole").asText(),false);
            }else if("iqc-item-candidates-v1".equals(schema)){
                recognized++;quotes(value.path("quotes"),null,true);
            }else if(value.path("hit").isBoolean()&&value.path("reason").isTextual()&&value.has("evidence")){
                recognized++;quotes(value.get("evidence"),null,false);
            }else unmeasured++;
        }
        void quotes(JsonNode values,String requiredRole,boolean candidate){
            if(values==null||!values.isArray()){malformed++;return;}
            for(var quote:values){
                claimed++;var message=messages.get(quote.path("messageId").asText());
                boolean valid=LegacyFindingAdapter.citationGrounded(quote,message);
                if(valid&&requiredRole!=null)valid=!requiredRole.isBlank()&&requiredRole.equals(message.getSpeakerRole());
                if(valid&&(candidate||quote.has("conversationId")))valid=Objects.equals(quote.path("conversationId").asText(),message.getConversationId());
                if(valid&&(candidate||quote.has("speakerRole")))valid=Objects.equals(quote.path("speakerRole").asText(),message.getSpeakerRole());
                if(!valid)invalid++;
            }
        }
    }
}
