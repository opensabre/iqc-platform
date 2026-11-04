package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Append-only execution evidence for one task execution and conversation, shared by result processors. */
public record EngineExecutionResultSet(
        String schemaVersion, String resultSetId, String taskId, String executionId, String conversationId,
        InputSnapshot input, JsonNode taskConfiguration, List<EngineResult> engines) {
    public static final String SCHEMA_VERSION = "iqc-engine-results-v1";
    public enum Operation { INSPECTION, LABELING }
    public enum EngineType { REGEX, DLS, LLM }
    public enum ExecutionStatus { SUCCEEDED, FAILED, SKIPPED }
    public enum ObservationStatus { DETERMINED, UNKNOWN, INVALID }

    public EngineExecutionResultSet {
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("不支持的引擎结果集版本");
        required(resultSetId, "结果集 ID"); required(taskId, "任务 ID"); required(executionId, "执行 ID");
        required(conversationId, "会话 ID"); Objects.requireNonNull(input, "输入快照不能为空");
        taskConfiguration = copy(taskConfiguration); engines = List.copyOf(engines);
        var resultIds = new HashSet<String>(); var instanceIds = new HashSet<String>(); var positions = new HashSet<String>();
        var messages = input.messages().stream().collect(java.util.stream.Collectors.toMap(Message::messageId, m -> m));
        for (EngineResult engine : engines) {
            if (!resultIds.add(engine.engineResultId()) || !instanceIds.add(engine.operation() + ":" + engine.engineInstanceId())
                    || !positions.add(engine.operation() + ":" + engine.sequence()))
                throw new IllegalArgumentException("同一操作的引擎实例、顺序或结果 ID 重复");
            var evidenceIds = new HashSet<String>();
            for (Evidence evidence : engine.evidence()) {
                if (!evidenceIds.add(evidence.evidenceId())) throw new IllegalArgumentException("引擎证据 ID 重复");
                Message message = messages.get(evidence.messageId());
                if (message == null || evidence.end() > message.content().length()
                        || !message.content().substring(evidence.start(), evidence.end()).equals(evidence.quote()))
                    throw new IllegalArgumentException("证据必须精确指向本次输入快照的原始消息");
            }
            for (Observation observation : engine.observations())
                if (!evidenceIds.containsAll(observation.evidenceIds())) throw new IllegalArgumentException("结果引用了不存在的引擎证据");
        }
    }

    /** Defensive copies prevent one processor from changing inputs seen by another processor. */
    @Override public JsonNode taskConfiguration() { return taskConfiguration.deepCopy(); }

    /** Message text is frozen once per conversation, not copied once for each engine. */
    public record InputSnapshot(String snapshotId, Instant capturedAt, List<Message> messages) {
        public InputSnapshot {
            required(snapshotId, "输入快照 ID"); Objects.requireNonNull(capturedAt); messages = List.copyOf(messages);
            if (messages.stream().map(Message::messageId).distinct().count() != messages.size())
                throw new IllegalArgumentException("输入快照消息 ID 重复");
        }
    }
    public record Message(String messageId, int sequence, String speakerRole, String relativeTime, String content) {
        public Message { required(messageId, "消息 ID"); required(speakerRole, "说话角色"); Objects.requireNonNull(content); }
    }

    /** A result exists even on engine failure; raw output and partial observations are retained. */
    public record EngineResult(String engineResultId, Operation operation, String engineInstanceId, EngineType engineType,
                               int sequence, Instant startedAt, Instant finishedAt, ExecutionStatus status,
                               JsonNode configuration, String rawOutput, List<Observation> observations,
                               List<Evidence> evidence, Failure failure) {
        public EngineResult {
            required(engineResultId, "引擎结果 ID"); required(engineInstanceId, "引擎实例 ID");
            Objects.requireNonNull(operation); Objects.requireNonNull(engineType); Objects.requireNonNull(status);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(finishedAt);
            if (sequence < 1 || finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("执行顺序或时间不合法");
            configuration = copy(configuration); observations = List.copyOf(observations); evidence = List.copyOf(evidence);
            if (status == ExecutionStatus.FAILED && failure == null) throw new IllegalArgumentException("失败引擎必须记录失败原因");
            if (status == ExecutionStatus.SUCCEEDED && failure != null) throw new IllegalArgumentException("成功引擎不能附带失败状态");
        }
        @Override public JsonNode configuration() { return configuration.deepCopy(); }
    }

    /** A normalized engine observation is not a final score or a committed label. */
    public record Observation(String targetId, ObservationStatus status, JsonNode value, BigDecimal confidence,
                              String reason, List<String> evidenceIds) {
        public Observation {
            required(targetId, "识别目标 ID"); Objects.requireNonNull(status);
            value = value == null ? com.fasterxml.jackson.databind.node.NullNode.instance : value.deepCopy();
            evidenceIds = List.copyOf(evidenceIds);
            if (confidence != null && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0))
                throw new IllegalArgumentException("置信度必须在 0 到 1 之间");
        }
        @Override public JsonNode value() { return value.deepCopy(); }
    }
    /** Offsets are UTF-16 character offsets, end-exclusive, into this result set's frozen message. */
    public record Evidence(String evidenceId, String messageId, int start, int end, String quote) {
        public Evidence {
            required(evidenceId, "证据 ID"); required(messageId, "证据消息 ID"); Objects.requireNonNull(quote);
            if (start < 0 || end <= start || quote.isEmpty()) throw new IllegalArgumentException("证据区间不合法");
        }
    }
    public record Failure(String code, String message) {
        public Failure { required(code, "错误码"); required(message, "失败原因"); }
    }
    private static JsonNode copy(JsonNode value) { return Objects.requireNonNull(value, "配置快照不能为空").deepCopy(); }
    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    }
}
