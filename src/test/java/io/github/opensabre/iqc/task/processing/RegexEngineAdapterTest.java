package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;

class RegexEngineAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RegexEngineAdapter adapter = new RegexEngineAdapter(mapper);
    private final Instant now = Instant.parse("2026-11-05T00:00:00Z");
    private final InputSnapshot input = new InputSnapshot("input", now, List.of(
            new Message("customer", 1, "customer", "00:01", "😀退款进度"),
            new Message("agent", 2, "agent", "00:02", "正在查询")));

    @Test void preservesActualMatchOutputAndUtf16EvidenceWithoutFinalScoring() throws Exception {
        var output = adapter.execute(input, config("退款", "customer"));
        assertThat(output.observations().getFirst().value().booleanValue()).isTrue();
        assertThat(output.evidence()).containsExactly(new Evidence("match-1", "customer", 2, 4, "退款"));
        var raw = mapper.readTree(output.rawOutput());
        assertThat(raw.size()).isEqualTo(1);
        assertThat(raw.get(0).path("start").asInt()).isEqualTo(2);
        assertThat(raw.get(0).path("text").asText()).isEqualTo("退款");
    }

    @Test void distinguishesNoMatchFromNoEligibleInputAndAllowsZeroWidthPatterns() throws Exception {
        var noMatch = adapter.execute(input, config("不存在", "customer"));
        assertThat(noMatch.observations().getFirst().value().booleanValue()).isFalse();
        assertThat(noMatch.observations().getFirst().status()).isEqualTo(ObservationStatus.DETERMINED);
        var empty = adapter.execute(input, config("退款", "supervisor"));
        assertThat(empty.observations().getFirst().status()).isEqualTo(ObservationStatus.UNKNOWN);
        assertThat(empty.observations().getFirst().value().isNull()).isTrue();
        var zeroWidth = adapter.execute(input, config("^", "customer"));
        assertThat(zeroWidth.observations().getFirst().value().booleanValue()).isTrue();
        assertThat(zeroWidth.evidence()).isEmpty();
    }

    @Test void invalidRegexDoesNotPreventLaterLabelingEngineAndCheckpointRoundTrips() {
        var saved = new java.util.ArrayList<EngineExecutionResultSet>();
        var initial = new EngineExecutionResultSet(SCHEMA_VERSION, "result", "task", "execution", "conversation", input,
                mapper.createObjectNode(), List.of());
        var executor = new MultiEngineExecutor(Clock.fixed(now, ZoneOffset.UTC));
        var result = executor.execute(initial, List.of(
                new MultiEngineExecutor.Step(Operation.INSPECTION, "bad", EngineType.REGEX, 1, config("[", "customer"), adapter),
                new MultiEngineExecutor.Step(Operation.LABELING, "good", EngineType.REGEX, 1, config("退款", "customer"), adapter)),
                () -> false, checkpoint -> {
                    try { saved.add(mapper.readValue(mapper.writeValueAsString(checkpoint), EngineExecutionResultSet.class)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
        assertThat(saved).hasSize(3);
        assertThat(result.engines().get(0).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.engines().get(1).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.getLast()).isEqualTo(result);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode config(String expression, String role) {
        return mapper.createObjectNode().put("targetId", "target-1").put("expression", expression).put("speakerRole", role);
    }
}
