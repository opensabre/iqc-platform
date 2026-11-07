package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;

class MultiEngineExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-05T00:00:00Z");
    private final MultiEngineExecutor executor = new MultiEngineExecutor(Clock.fixed(now, ZoneOffset.UTC));

    @Test void recordsFailureAndContinuesBothOperationsInConfiguredOrder() {
        var calls = new ArrayList<String>();
        var snapshots = new ArrayList<EngineExecutionResultSet>();
        var result = executor.execute(empty(), List.of(
                step(Operation.LABELING, "label", 1, (i, c) -> { calls.add("label"); return ok(); }),
                step(Operation.INSPECTION, "second", 2, (i, c) -> { calls.add("second"); return ok(); }),
                step(Operation.INSPECTION, "first", 1, (i, c) -> { calls.add("first");
                    return new MultiEngineExecutor.Output("{broken", List.of(), List.of(), new Failure("PARSE_ERROR", "解析失败")); })),
                () -> false, snapshots::add);
        assertThat(calls).containsExactly("first", "second", "label");
        assertThat(snapshots).hasSize(4);
        assertThat(snapshots.get(0).engines()).isEmpty();
        assertThat(result.engines()).extracting(EngineResult::status)
                .containsExactly(ExecutionStatus.FAILED, ExecutionStatus.SUCCEEDED, ExecutionStatus.SUCCEEDED);
        assertThat(result.engines().get(0).rawOutput()).isEqualTo("{broken");
    }

    @Test void storageFailureStopsCallsAndSavedCheckpointResumesWithoutRepeatingCompletedEngine() {
        var calls = new AtomicInteger(); var durable = new ArrayList<EngineExecutionResultSet>();
        var plan = List.of(step(Operation.INSPECTION, "one", 1, (i, c) -> { calls.incrementAndGet(); return ok(); }),
                step(Operation.INSPECTION, "two", 2, (i, c) -> { calls.incrementAndGet(); return ok(); }));
        assertThatThrownBy(() -> executor.execute(empty(), plan, () -> false, snapshot -> {
            if (snapshot.engines().size() == 2) throw new IllegalStateException("storage unavailable");
            durable.add(snapshot);
        })).hasMessage("storage unavailable");
        assertThat(calls).hasValue(2);
        var resumed = executor.execute(durable.get(durable.size() - 1), plan, () -> false, durable::add);
        assertThat(calls).hasValue(3); // Only the uncommitted second call is retried.
        assertThat(resumed.engines()).hasSize(2);
        assertThat(resumed.engines().get(0)).isEqualTo(durable.get(1).engines().get(0));
    }

    @Test void rejectsFabricatedEvidenceButRetainsRawResponseAndContinues() {
        var result = executor.execute(empty(), List.of(step(Operation.INSPECTION, "bad", 1, (i, c) ->
                new MultiEngineExecutor.Output("raw fabricated quotation", List.of(),
                        List.of(new Evidence("e1", "m1", 0, 2, "购买")), null)),
                step(Operation.INSPECTION, "good", 2, (i, c) -> ok())), () -> false, ignored -> {});
        assertThat(result.engines().get(0).failure().code()).isEqualTo("INVALID_EVIDENCE");
        assertThat(result.engines().get(0).rawOutput()).isEqualTo("raw fabricated quotation");
        assertThat(result.engines().get(1).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test void cancellationProducesSkippedRecordsWithoutCallingAdapters() {
        var calls = new AtomicInteger();
        var result = executor.execute(empty(), List.of(step(Operation.LABELING, "one", 1, (i, c) -> {
            calls.incrementAndGet(); return ok(); })), () -> true, ignored -> {});
        assertThat(calls).hasValue(0);
        assertThat(result.engines().get(0).status()).isEqualTo(ExecutionStatus.SKIPPED);
    }

    private MultiEngineExecutor.Step step(Operation operation, String id, int sequence, MultiEngineExecutor.Adapter adapter) {
        return new MultiEngineExecutor.Step(operation, id, EngineType.REGEX, sequence, mapper.createObjectNode(), adapter);
    }
    private MultiEngineExecutor.Output ok() { return new MultiEngineExecutor.Output("{}", List.of(), List.of(), null); }
    private EngineExecutionResultSet empty() {
        return new EngineExecutionResultSet(SCHEMA_VERSION, "rs1", "t1", "ex1", "c1",
                new InputSnapshot("s1", now, List.of(new Message("m1", 1, "customer", "00:01", "退款进度"))),
                mapper.createObjectNode(), List.of());
    }
}
