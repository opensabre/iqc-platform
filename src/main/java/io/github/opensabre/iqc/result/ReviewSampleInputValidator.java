package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import java.util.List;

/** Reuses the production input fingerprint so reviewed cases cannot silently capture edited transcripts. */
public final class ReviewSampleInputValidator {
    private ReviewSampleInputValidator() { }
    /** Preserves the legacy fingerprint contract for results without frozen production inputs. */
    public static String validate(String traceJson, List<ConversationMessage> messages, ObjectMapper json) {
        return validateInput(traceJson,messages,null,null,json).executionInputHash();
    }
    public record ValidatedInput(String executionInputHash, com.fasterxml.jackson.databind.JsonNode businessContext) {
        public ValidatedInput {businessContext=businessContext==null?null:businessContext.deepCopy();}
        @Override public com.fasterxml.jackson.databind.JsonNode businessContext(){return businessContext==null?null:businessContext.deepCopy();}
    }
    /** Reuses the original envelope validator; archive metadata is not part of frozen execution fields. */
    public static ValidatedInput validateInput(String traceJson, List<ConversationMessage> messages,
            io.github.opensabre.iqc.task.model.InspectionTask task,String conversationId,ObjectMapper json) {
        try {
            if (traceJson == null || traceJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
                throw new IllegalArgumentException();
            var trace = json.readTree(traceJson);
            // Normalize exactly as production does, retaining the original transcript for candidate storage.
            var normalized = messages.stream().map(message -> {
                var copy = json.convertValue(message, ConversationMessage.class);
                copy.setSpeakerRole(io.github.opensabre.iqc.conversation.ConversationSpeakerRole.canonical(message.getSpeakerRole()));
                return copy;
            }).toList();
            com.fasterxml.jackson.databind.JsonNode businessContext=null;
            String actual;
            var snapshot=task==null?null:json.readTree(task.getRuleSnapshotJson());
            if(snapshot!=null && snapshot.has("productionInputs")) {
                if(!"iqc-pipeline-execution-v1".equals(trace.path("schemaVersion").asText()))throw new IllegalArgumentException();
                var prepared=new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService(null,null,null,json).prepare(task,snapshot);
                var frozen=prepared.messages().get(conversationId);
                if(frozen==null || !executionFields(frozen).equals(executionFields(normalized))
                        || normalized.stream().anyMatch(message->!java.util.Objects.equals(conversationId,message.getConversationId())))
                    throw new IllegalArgumentException();
                businessContext=prepared.businessContext(conversationId);
                var input=json.createObjectNode();input.set("messages",json.valueToTree(frozen));input.set("businessContext",businessContext);
                actual=PipelineAssetService.hash(input.toString());
            } else actual = switch (trace.path("schemaVersion").asText()) {
                case "iqc-route-execution-trace-v1" -> RouteExecutionTrace.inputHash(normalized);
                case "iqc-pipeline-execution-v1" -> {
                    var input = json.createObjectNode(); input.set("messages", json.valueToTree(normalized));
                    yield PipelineAssetService.hash(input.toString());
                }
                default -> throw new IllegalArgumentException();
            };
            if (!actual.equals(trace.path("inputHash").asText())) throw new IllegalArgumentException();
            return new ValidatedInput(actual,businessContext);
        } catch (Exception exception) {
            throw IqcException.invalidState("无法确认候选会话与原执行输入一致，请重新执行并复核");
        }
    }
    private static List<io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.Message> executionFields(List<ConversationMessage> messages) {
        return messages.stream().map(message->new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.Message(
                message.getId(),message.getSequenceNo(),message.getSpeakerRole(),message.getContent(),message.getRelativeTime())).toList();
    }

}
