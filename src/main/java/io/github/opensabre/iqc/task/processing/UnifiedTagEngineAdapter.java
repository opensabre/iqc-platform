package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** One stage evaluates every applicable tag; upstream matches only filter the LLM input, never rewrite history. */
public final class UnifiedTagEngineAdapter implements MultiEngineExecutor.Adapter {
    private final ObjectMapper mapper;
    private final RegexEngineAdapter regex;
    private final DlsEngineAdapter dls;
    private final LlmEngineAdapter llm;
    private final EngineType type;
    public UnifiedTagEngineAdapter(ObjectMapper mapper, RegexEngineAdapter regex, DlsEngineAdapter dls,
                                   LlmEngineAdapter llm, EngineType type) {
        this.mapper = mapper; this.regex = regex; this.dls = dls; this.llm = llm; this.type = type;
    }
    @Override public MultiEngineExecutor.Output execute(InputSnapshot input, JsonNode config) throws Exception {
        return execute(input, config, List.of());
    }
    /** Only explicit MATCHED observations from successful or partially successful prior stages can filter a tag. */
    @Override public MultiEngineExecutor.Output execute(InputSnapshot input, JsonNode config, List<EngineResult> upstream) throws Exception {
        var tags = new ArrayList<JsonNode>();
        var matched = new HashSet<String>();
        for (var prior : upstream) if (prior.status() == ExecutionStatus.SUCCEEDED || prior.status() == ExecutionStatus.PARTIAL_SUCCESS)
            prior.observations().stream().filter(o -> o.status() == ObservationStatus.MATCHED).forEach(o -> matched.add(o.targetId()));
        for (var tag : config.path("tags")) if (type != EngineType.LLM || !matched.contains(tag.path("tagId").asText())) tags.add(tag);
        var metadata = mapper.createObjectNode().put("applicableTagCount", config.path("tags").size())
                .put("pendingTagCount", tags.size()).put("filteredTagCount", config.path("tags").size() - tags.size())
                .put("modelCalled", false);
        var observations = new ArrayList<Observation>();
        var evidence = new ArrayList<Evidence>();
        long started = System.nanoTime();
        if (tags.isEmpty()) return output(tags, observations, evidence, metadata, null, started, null, ExecutionStatus.SKIPPED);
        if (type == EngineType.LLM) {
            var request = mapper.createObjectNode().put("conversationId", config.path("conversationId").asText());
            request.set("conversation", mapper.valueToTree(input.messages()));
            var pending = request.putArray("tags");
            for (var tag : tags) pending.addObject().put("tagId", tag.path("tagId").asText()).put("tagCode", tag.path("tagCode").asText())
                    .put("ruleId", tag.path("ruleId").asText()).put("prompt", tag.path("expression").asText());
            // A bounded single request is deliberate: never split or silently truncate labels or conversation.
            if (request.toString().length() > 150_000) {
                tags.forEach(t -> observations.add(observation(t, ObservationStatus.ERROR, "REQUEST_TOO_LARGE")));
                return output(tags, observations, evidence, metadata, null, started,
                        new Failure("REQUEST_TOO_LARGE", "批量标签请求超过 150000 字符，未调用模型"), ExecutionStatus.FAILED);
            }
            JsonNode response;
            try {
                metadata.put("modelCalled", true);
                response = llm.evaluateTagBatch(request, config.path("agentSnapshot"), input.snapshotId() + ":tags");
            } catch (RuntimeException failure) {
                tags.forEach(t -> observations.add(observation(t, ObservationStatus.ERROR, "LLM_CALL_FAILED")));
                return output(tags, observations, evidence, metadata, null, started,
                        new Failure("LLM_CALL_FAILED", failure.getClass().getSimpleName()), ExecutionStatus.FAILED);
            }
            var expected = new HashSet<String>(); tags.forEach(t -> expected.add(t.path("tagId").asText()));
            var rows = new HashMap<String, JsonNode>(); var duplicates = new HashSet<String>();
            boolean invalid = response == null || !response.path("results").isArray();
            if (!invalid) for (var row : response.path("results")) {
                String id = row.path("tagId").asText();
                if (!row.path("tagId").isTextual() || !expected.contains(id)) { invalid = true; continue; }
                if (rows.putIfAbsent(id, row) != null) { duplicates.add(id); invalid = true; }
            }
            for (var tag : tags) {
                String id = tag.path("tagId").asText(); var row = rows.get(id);
                ObservationStatus status;
                try {
                    status = row == null || duplicates.contains(id) || !row.path("status").isTextual() ? ObservationStatus.ERROR
                            : ObservationStatus.valueOf(row.path("status").asText());
                    if (!List.of(ObservationStatus.MATCHED, ObservationStatus.NOT_MATCHED, ObservationStatus.UNKNOWN,
                            ObservationStatus.ERROR, ObservationStatus.SKIPPED).contains(status)) status = ObservationStatus.ERROR;
                } catch (IllegalArgumentException unsupported) { status = ObservationStatus.ERROR; }
                if (status == ObservationStatus.ERROR) invalid = true;
                observations.add(observation(tag, status, row == null ? "MISSING_RESULT" : row.path("reason").asText(null)));
            }
            if (response != null && response.path("metadata").isObject()) metadata.set("provider", response.path("metadata"));
            var status = invalid ? observations.stream().anyMatch(o -> o.status() != ObservationStatus.ERROR)
                    ? ExecutionStatus.PARTIAL_SUCCESS : ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
            return output(tags, observations, evidence, metadata, response, started,
                    invalid ? new Failure("INVALID_BATCH_RESPONSE", "批量响应包含缺失、重复、越界或无效状态") : null, status);
        }
        for (var tag : tags) {
            try {
                var parameters = mapper.createObjectNode().put("targetId", tag.path("tagId").asText())
                        .put("expression", tag.path("expression").asText());
                String role = tag.path("speakerRole").asText("");
                if (!role.isBlank() && !"all".equalsIgnoreCase(role)) parameters.put("speakerRole", role);
                MultiEngineExecutor.Adapter adapter = type == EngineType.REGEX ? regex : dls;
                var result = adapter.execute(input, parameters);
                var original = result.observations().getFirst();
                var ids = new ArrayList<String>();
                for (var quote : result.evidence()) {
                    String id = tag.path("tagId").asText() + ":" + quote.evidenceId(); ids.add(id);
                    evidence.add(new Evidence(id, quote.messageId(), quote.start(), quote.end(), quote.quote()));
                }
                observations.add(new Observation(original.targetId(), original.status() == ObservationStatus.DETERMINED
                        ? original.value().asBoolean() ? ObservationStatus.MATCHED : ObservationStatus.NOT_MATCHED
                        : ObservationStatus.UNKNOWN, original.value(), original.confidence(), original.reason(), ids));
            } catch (Exception failure) { observations.add(observation(tag, ObservationStatus.ERROR, failure.getClass().getSimpleName())); }
        }
        boolean errors = observations.stream().anyMatch(o -> o.status() == ObservationStatus.ERROR);
        var status = !errors ? ExecutionStatus.SUCCEEDED : observations.stream().allMatch(o -> o.status() == ObservationStatus.ERROR)
                ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL_SUCCESS;
        return output(tags, observations, evidence, metadata, null, started,
                errors ? new Failure("TAG_EVALUATION_ERROR", "部分或全部标签规则执行失败") : null, status);
    }
    private Observation observation(JsonNode tag, ObservationStatus status, String reason) {
        return new Observation(tag.path("tagId").asText(), status,
                status == ObservationStatus.MATCHED || status == ObservationStatus.NOT_MATCHED
                        ? mapper.getNodeFactory().booleanNode(status == ObservationStatus.MATCHED) : null,
                null, reason, List.of());
    }
    private MultiEngineExecutor.Output output(List<JsonNode> tags, List<Observation> observations, List<Evidence> evidence,
                                               ObjectNode metadata, JsonNode response, long started, Failure failure, ExecutionStatus status) {
        metadata.put("elapsedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        // Keep provider-reported telemetry separate from framework filter counts and elapsed time.
        if (response != null && response.path("metadata").isObject())
            metadata.set("provider", response.path("metadata").deepCopy());
        var payload = mapper.createObjectNode().put("engineType", type.name()).put("status", switch (status) {
            case SUCCEEDED -> "SUCCESS"; case PARTIAL_SUCCESS -> "PARTIAL_SUCCESS"; case FAILED -> "ERROR"; case SKIPPED -> "SKIPPED";
        });
        payload.set("metadata", metadata);
        var results = payload.putArray("tagResults");
        for (var observation : observations) {
            var tag = tags.stream().filter(t -> observation.targetId().equals(t.path("tagId").asText())).findFirst().orElseThrow();
            var row = results.addObject().put("tagId", observation.targetId()).put("ruleId", tag.path("ruleId").asText())
                    .put("ruleVersionNo", tag.path("ruleVersionNo").asInt()).put("status", observation.status().name());
            if (observation.reason() != null) row.put("reason", observation.reason());
            row.set("evidenceIds", mapper.valueToTree(observation.evidenceIds()));
        }
        // Keep provider output as untrusted stage data; never promote its prose to located evidence.
        if (response != null) payload.set("providerResponse", response);
        return new MultiEngineExecutor.Output(payload.toString(), observations, evidence, failure, status);
    }
}
