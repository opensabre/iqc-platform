package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Executes the configured engines in order and checkpoints evidence before invoking another engine. */
public final class MultiEngineExecutor {
    private final Clock clock;
    public MultiEngineExecutor(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    /** Adapter output keeps the actual response even when parsing or evidence validation fails. */
    public record Output(String rawOutput, List<Observation> observations, List<Evidence> evidence, Failure failure) {
        public Output { observations = List.copyOf(observations); evidence = List.copyOf(evidence); }
    }
    @FunctionalInterface
    public interface Adapter { Output execute(InputSnapshot input, JsonNode configuration) throws Exception; }
    /** Each operation has its own sequence; the adapter receives a defensive configuration copy. */
    public record Step(Operation operation, String instanceId, EngineType type, int sequence,
                       JsonNode configuration, Adapter adapter) {
        public Step {
            Objects.requireNonNull(operation); Objects.requireNonNull(type); Objects.requireNonNull(adapter);
            if (instanceId == null || instanceId.isBlank() || sequence < 1) throw new IllegalArgumentException("引擎实例或顺序不合法");
            configuration = Objects.requireNonNull(configuration).deepCopy();
        }
        @Override public JsonNode configuration() { return configuration.deepCopy(); }
    }
    @FunctionalInterface
    public interface Checkpoint { void save(EngineExecutionResultSet resultSet); }

    /** Resumption consumes the saved input and results, never rerunning completed engine instances. */
    public EngineExecutionResultSet execute(EngineExecutionResultSet saved, List<Step> plan,
                                            BooleanSupplier cancelled, Checkpoint checkpoint) {
        Objects.requireNonNull(cancelled); Objects.requireNonNull(checkpoint);
        var ordered = plan.stream().sorted(Comparator.comparing(Step::operation).thenComparingInt(Step::sequence)).toList();
        var instances = new HashSet<String>(); var positions = new HashSet<String>();
        for (var step : ordered) {
            if (!instances.add(key(step.operation(), step.instanceId())) || !positions.add(step.operation() + ":" + step.sequence()))
                throw new IllegalArgumentException("引擎实例或执行顺序重复");
        }
        for (var previous : saved.engines()) {
            var step = ordered.stream().filter(s -> key(s.operation(), s.instanceId()).equals(key(previous.operation(), previous.engineInstanceId())))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("恢复计划缺少已记录引擎"));
            if (step.type() != previous.engineType() || step.sequence() != previous.sequence()
                    || !step.configuration().equals(previous.configuration())) throw new IllegalArgumentException("恢复时不能改变引擎配置");
        }
        checkpoint.save(saved); // Input must be durable before the first external call.
        var results = new ArrayList<>(saved.engines());
        boolean stopped = false;
        for (var step : ordered) {
            if (results.stream().anyMatch(r -> key(r.operation(), r.engineInstanceId()).equals(key(step.operation(), step.instanceId())))) continue;
            var started = clock.instant();
            Output output;
            ExecutionStatus status;
            stopped = stopped || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
            if (stopped) {
                output = new Output(null, List.of(), List.of(), new Failure("CANCELLED", "任务已取消，未调用引擎"));
                status = ExecutionStatus.SKIPPED;
            } else {
                try {
                    output = Objects.requireNonNull(step.adapter().execute(saved.input(), step.configuration()), "引擎返回空结果");
                    status = output.failure() == null ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); stopped = true;
                    output = new Output(null, List.of(), List.of(), new Failure("INTERRUPTED", "引擎调用被中断"));
                    status = ExecutionStatus.FAILED;
                } catch (Exception failure) {
                    output = new Output(null, List.of(), List.of(), new Failure("ENGINE_EXCEPTION", failure.getClass().getSimpleName()));
                    status = ExecutionStatus.FAILED;
                }
            }
            var result = result(step, started, status, output);
            var candidate = new ArrayList<>(results); candidate.add(result);
            EngineExecutionResultSet next;
            try {
                next = withResults(saved, candidate);
            } catch (IllegalArgumentException invalidEvidence) {
                // Invalid quotations cannot become evidence, but the original response is still retained.
                result = result(step, started, ExecutionStatus.FAILED,
                        new Output(output.rawOutput(), List.of(), List.of(), new Failure("INVALID_EVIDENCE", invalidEvidence.getMessage())));
                candidate.set(candidate.size() - 1, result);
                next = withResults(saved, candidate);
            }
            checkpoint.save(next); // Storage failures propagate; they are never disguised as engine failures.
            results.add(result);
        }
        return withResults(saved, results);
    }

    private EngineResult result(Step step, java.time.Instant started, ExecutionStatus status, Output output) {
        return new EngineResult(UUID.randomUUID().toString(), step.operation(), step.instanceId(), step.type(), step.sequence(),
                started, clock.instant(), status, step.configuration(), output.rawOutput(), output.observations(), output.evidence(), output.failure());
    }
    private static EngineExecutionResultSet withResults(EngineExecutionResultSet source, List<EngineResult> results) {
        return new EngineExecutionResultSet(source.schemaVersion(), source.resultSetId(), source.taskId(), source.executionId(),
                source.conversationId(), source.input(), source.taskConfiguration(), results);
    }
    private static String key(Operation operation, String instance) { return operation + ":" + instance; }
}
