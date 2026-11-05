package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.rule.dls.DlsEngine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Evaluates the existing DLS language against the entire frozen conversation and retains its native output. */
@Component
public final class DlsEngineAdapter implements MultiEngineExecutor.Adapter {
    private final ObjectMapper mapper;
    public DlsEngineAdapter(ObjectMapper mapper) { this.mapper = mapper; }

    /** Configuration contains a targetId and the frozen, self-contained DLS expression string. */
    @Override public MultiEngineExecutor.Output execute(InputSnapshot input, JsonNode configuration) throws Exception {
        String target = text(configuration, "targetId");
        var compiled = DlsEngine.compile(mapper, text(configuration, "expression"));
        List<ConversationMessage> messages = input.messages().stream().map(source -> {
            var message = new ConversationMessage();
            message.setId(source.messageId()); message.setSequenceNo(source.sequence());
            message.setSpeakerRole(source.speakerRole()); message.setContent(source.content());
            return message;
        }).toList();
        var evaluation = DlsEngine.evaluate(compiled, messages);
        var evidence = new ArrayList<Evidence>();
        for (var hit : evaluation.evidence()) {
            // Zero-width matches are preserved in raw output without inventing nonempty quotations.
            if (hit.end() > hit.start()) evidence.add(new Evidence("dls-" + evidence.size(), hit.messageId(), hit.start(), hit.end(), hit.text()));
        }
        var observation = new Observation(target, messages.isEmpty() ? ObservationStatus.UNKNOWN : ObservationStatus.DETERMINED,
                messages.isEmpty() ? mapper.getNodeFactory().nullNode() : mapper.getNodeFactory().booleanNode(evaluation.hit()),
                null, evaluation.reason(), evidence.stream().map(Evidence::evidenceId).toList());
        return new MultiEngineExecutor.Output(mapper.writeValueAsString(evaluation), List.of(observation), evidence, null);
    }

    private static String text(JsonNode config, String field) {
        JsonNode value = config.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("DLS 引擎参数缺少 " + field);
        return value.asText();
    }
}
