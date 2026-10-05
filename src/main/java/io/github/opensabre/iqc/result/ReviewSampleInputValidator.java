package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import java.util.List;

/** Reuses the production input fingerprint so reviewed cases cannot silently capture edited transcripts. */
public final class ReviewSampleInputValidator {
    private ReviewSampleInputValidator() { }
    public static String validate(String traceJson, List<ConversationMessage> messages, ObjectMapper json) {
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
            String actual = switch (trace.path("schemaVersion").asText()) {
                case "iqc-route-execution-trace-v1" -> RouteExecutionTrace.inputHash(normalized);
                case "iqc-pipeline-execution-v1" -> {
                    var input = json.createObjectNode(); input.set("messages", json.valueToTree(normalized));
                    yield PipelineAssetService.hash(input.toString());
                }
                default -> throw new IllegalArgumentException();
            };
            if (!actual.equals(trace.path("inputHash").asText())) throw new IllegalArgumentException();
            return actual;
        } catch (Exception exception) {
            throw IqcException.invalidState("无法确认候选会话与原执行输入一致，请重新执行并复核");
        }
    }
}
