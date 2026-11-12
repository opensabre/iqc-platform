package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rule.dls.DlsRuleDocument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;

class DlsEngineAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DlsEngineAdapter adapter = new DlsEngineAdapter(mapper);
    private final Instant now = Instant.parse("2026-11-05T00:00:00Z");
    private final Message need = new Message("need", 1, "customer", "00:01", "😀退款");
    private final Message reply = new Message("reply", 2, "agent", "00:02", "正在查询");

    @Test void usesTheWholeConversationAndPreservesNativeDefinitionsAndOffsets() throws Exception {
        var output = adapter.execute(input(List.of(need, reply)), configuration());
        assertThat(output.observations().getFirst().value().booleanValue()).isTrue();
        assertThat(output.evidence()).extracting(Evidence::messageId).contains("need", "reply");
        assertThat(output.evidence()).anySatisfy(e -> {
            assertThat(e.messageId()).isEqualTo("need"); assertThat(e.start()).isEqualTo(2);
            assertThat(e.end()).isEqualTo(4); assertThat(e.quote()).isEqualTo("退款");
        });
        assertThat(mapper.readTree(output.rawOutput()).path("evidence").toString()).contains("rule_need", "rule_reply");
        // The original source list may be unordered; DLS uses the original message sequence.
        assertThat(adapter.execute(input(List.of(reply, need)), configuration()).observations().getFirst().value().booleanValue()).isTrue();
    }

    @Test void distinguishesAnUnsatisfiedSequenceFromAnEmptyConversation() throws Exception {
        var reversedReply = new Message("reply", 1, "agent", "00:01", "正在查询");
        var laterNeed = new Message("need", 2, "customer", "00:02", "退款");
        var reversed = adapter.execute(input(List.of(reversedReply, laterNeed)), configuration());
        assertThat(reversed.observations().getFirst().value().booleanValue()).isFalse();
        var empty = adapter.execute(input(List.of()), configuration());
        assertThat(empty.observations().getFirst().status()).isEqualTo(ObservationStatus.UNKNOWN);
        assertThat(empty.observations().getFirst().value().isNull()).isTrue();
    }

    @Test void brokenDlsDoesNotBlockTheFollowingRegexEngine() throws Exception {
        var broken = mapper.createObjectNode().put("targetId", "check").put("expression", "not JSON");
        var regex = mapper.createObjectNode().put("targetId", "label").put("expression", "退款");
        var initial = new EngineExecutionResultSet(SCHEMA_VERSION, "result", "task", "execution", "conversation",
                input(List.of(need, reply)), mapper.createObjectNode(), List.of());
        var result = new MultiEngineExecutor(Clock.fixed(now, ZoneOffset.UTC)).execute(initial, List.of(
                new MultiEngineExecutor.Step(Operation.INSPECTION, "dls", EngineType.DLS, 1, broken, adapter),
                new MultiEngineExecutor.Step(Operation.LABELING, "regex", EngineType.REGEX, 1, regex, new RegexEngineAdapter(mapper))),
                () -> false, ignored -> {});
        assertThat(result.engines().getFirst().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.engines().getLast().status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.engines().getLast().evidence()).hasSize(1);
    }

    private InputSnapshot input(List<Message> messages) { return new InputSnapshot("input", now, messages); }
    private com.fasterxml.jackson.databind.JsonNode configuration() throws Exception {
        var document = new DlsRuleDocument("1.0", null, List.of(
                new DlsRuleDocument.Definition("rule_need", "RULE", "退款", "customer"),
                new DlsRuleDocument.Definition("rule_reply", "RULE", "查询", "agent")),
                "应答顺序", "[rule_need]%[rule_reply]");
        return mapper.createObjectNode().put("targetId", "response-order").put("expression", mapper.writeValueAsString(document));
    }
}
