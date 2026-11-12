package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.rule.RuleMatcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Reuses IQC's regex matcher and preserves per-message match output without assigning scores or labels. */
@Component
public final class RegexEngineAdapter implements MultiEngineExecutor.Adapter {
    private final ObjectMapper mapper;
    public RegexEngineAdapter(ObjectMapper mapper) { this.mapper = mapper; }

    /** Configuration is {targetId, expression, speakerRole?}; no match remains an explicit false observation. */
    @Override public MultiEngineExecutor.Output execute(InputSnapshot input, JsonNode configuration) throws Exception {
        String target = requiredText(configuration, "targetId");
        String expression = requiredText(configuration, "expression");
        if (configuration.has("speakerRole") && !configuration.get("speakerRole").isTextual())
            throw new IllegalArgumentException("speakerRole 必须是文本");
        String role = configuration.path("speakerRole").asText("");
        RuleMatcher.validate("REGEX", expression, mapper);
        var raw = mapper.createArrayNode();
        var evidence = new ArrayList<Evidence>();
        boolean hit = false;
        int evaluated = 0;
        for (Message message : input.messages()) {
            if (!role.isBlank() && !role.equals(message.speakerRole())) continue;
            var source = new ConversationMessage();
            source.setContent(message.content()); source.setSpeakerRole(message.speakerRole());
            var match = RuleMatcher.evaluate(mapper, "REGEX", expression, source);
            evaluated++;
            var row = raw.addObject().put("messageId", message.messageId()).put("hit", match.hit());
            if (match.hit()) {
                hit = true;
                row.put("text", match.text()).put("start", match.start()).put("end", match.end());
                // A zero-width match is real, but is not a quotation of nonempty source text.
                if (match.end() > match.start()) evidence.add(new Evidence("match-" + evaluated,
                        message.messageId(), match.start(), match.end(), match.text()));
            }
        }
        var observation = new Observation(target, evaluated == 0 ? ObservationStatus.UNKNOWN : ObservationStatus.DETERMINED,
                evaluated == 0 ? mapper.getNodeFactory().nullNode() : mapper.getNodeFactory().booleanNode(hit), null,
                evaluated == 0 ? "没有符合说话角色的消息" : null, evidence.stream().map(Evidence::evidenceId).toList());
        return new MultiEngineExecutor.Output(mapper.writeValueAsString(raw), List.of(observation), evidence, null);
    }

    private static String requiredText(JsonNode config, String field) {
        JsonNode value = config.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("正则引擎参数缺少 " + field);
        return value.asText();
    }
}
