package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

class EngineExecutionResultSetTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-11-05T00:00:00Z");

    @Test void retainsAllEngineOutputsAndFailuresForSeparateProcessors() throws Exception {
        var source = new ArrayList<EngineResult>(); source.add(engine("first", 1, ExecutionStatus.SUCCEEDED, null, List.of()));
        source.add(engine("failed", 2, ExecutionStatus.FAILED, "{broken-json", List.of()));
        source.add(engine("later", 3, ExecutionStatus.SUCCEEDED, "{\"matched\":true}", List.of()));
        var set = set(source); source.clear();
        assertThat(set.engines()).hasSize(3);
        assertThat(set.engines().get(1).rawOutput()).isEqualTo("{broken-json");
        assertThat(set.engines().get(1).failure().code()).isEqualTo("INVALID_OUTPUT");
        assertThat(set.engines().get(2).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        var restored = mapper.readValue(mapper.writeValueAsString(set), EngineExecutionResultSet.class);
        assertThat(restored.engines()).isEqualTo(set.engines());
        assertThatThrownBy(() -> set.engines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void processorsCannotMutateOriginalConfigurationOrObservationValues() {
        ObjectNode config = mapper.createObjectNode().put("temperature", 0);
        var observation = new Observation("label-1", ObservationStatus.DETERMINED, config, null, null, List.of());
        config.put("temperature", 1);
        ((ObjectNode) observation.value()).put("temperature", 2);
        assertThat(observation.value().path("temperature").asInt()).isZero();
        var set = set(List.of(engine("e1", 1, ExecutionStatus.SUCCEEDED, null, List.of())));
        ((ObjectNode) set.taskConfiguration()).put("changed", true);
        assertThat(set.taskConfiguration().has("changed")).isFalse();
    }

    @Test void rejectsInventedOrOutOfRangeEvidenceAndDuplicateEngineInstances() {
        var valid = new Evidence("quote-1", "message-1", 0, 2, "退款");
        assertThat(set(List.of(engine("valid", 1, ExecutionStatus.SUCCEEDED, null, List.of(valid)))).engines()).hasSize(1);
        assertThatThrownBy(() -> set(List.of(engine("fake", 1, ExecutionStatus.SUCCEEDED, null,
                List.of(new Evidence("quote-2", "message-1", 0, 2, "购买")))))).hasMessageContaining("原始消息");
        assertThatThrownBy(() -> set(List.of(engine("e1", 1, ExecutionStatus.SUCCEEDED, null, List.of()),
                engine("e1", 2, ExecutionStatus.SUCCEEDED, null, List.of())))).hasMessageContaining("重复");
    }

    private EngineExecutionResultSet set(List<EngineResult> engines) {
        return new EngineExecutionResultSet(SCHEMA_VERSION, "result-set-1", "task-1", "execution-1", "conversation-1",
                new InputSnapshot("input-1", now, List.of(new Message("message-1", 1, "customer", "00:01", "退款进度"))),
                mapper.createObjectNode(), engines);
    }
    private EngineResult engine(String id, int sequence, ExecutionStatus status, String raw, List<Evidence> evidence) {
        return new EngineResult(id, Operation.INSPECTION, id, EngineType.REGEX, sequence, now, now, status,
                mapper.createObjectNode(), raw, List.of(), evidence,
                status == ExecutionStatus.FAILED ? new Failure("INVALID_OUTPUT", "原始输出无法解析") : null);
    }
}
