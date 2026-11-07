package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Reuses model routing and retry governance while retaining every actual response before parsing. */
@Component
public final class LlmEngineAdapter implements MultiEngineExecutor.Adapter {
    private final LlmQualityProvider provider;
    private final ObjectMapper mapper;
    public LlmEngineAdapter(LlmQualityProvider provider, ObjectMapper mapper) {
        this.provider = provider; this.mapper = mapper;
    }

    /** conversationId and published agent/rule snapshots must be supplied by the execution service. */
    @Override public MultiEngineExecutor.Output execute(InputSnapshot input, JsonNode configuration) throws Exception {
        String target = text(configuration, "targetId");
        String conversation = text(configuration, "conversationId");
        JsonNode rule = configuration.get("rule");
        JsonNode agent = configuration.get("agentSnapshot");
        if (rule == null || !rule.isObject() || agent == null || !agent.isObject())
            throw new IllegalArgumentException("LLM 引擎需要冻结的规则与智能体配置");
        var responses = new ArrayList<String>();
        try {
            var messages = input.messages().stream().map(source -> {
                var message = new ConversationMessage();
                message.setId(source.messageId()); message.setConversationId(conversation);
                message.setSequenceNo(source.sequence()); message.setSpeakerRole(source.speakerRole());
                message.setContent(source.content()); return message;
            }).toList();
            var evaluation = provider.evaluateConversationTraced(messages, rule.deepCopy(), agent.deepCopy(),
                    UUID.randomUUID().toString(), responses::add);
            String raw = responses.isEmpty() ? null : mapper.writeValueAsString(responses);
            if (!evaluation.supported()) return new MultiEngineExecutor.Output(raw, List.of(), List.of(),
                    new Failure("LLM_EVALUATION_FAILED", evaluation.reason()));
            // No quotations are inferred from prose. Complete model output remains available to result processors.
            var observation = new Observation(target, ObservationStatus.DETERMINED,
                    mapper.getNodeFactory().booleanNode(evaluation.hit()), null, evaluation.reason(), List.of());
            return new MultiEngineExecutor.Output(raw, List.of(observation), List.of(), null);
        } catch (RuntimeException exception) {
            return new MultiEngineExecutor.Output(responses.isEmpty() ? null : mapper.writeValueAsString(responses),
                    List.of(), List.of(), new Failure("LLM_ADAPTER_FAILED", exception.getClass().getSimpleName()));
        }
    }

    private static String text(JsonNode config, String field) {
        JsonNode value = config.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("LLM 引擎参数缺少 " + field);
        return value.asText();
    }
}
