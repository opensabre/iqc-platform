package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedTagEngineAdapterTest {
    final ObjectMapper mapper = new ObjectMapper();
    final LlmQualityProvider provider = mock(LlmQualityProvider.class);
    final RegexEngineAdapter regex = new RegexEngineAdapter(mapper);
    final DlsEngineAdapter dls = new DlsEngineAdapter(mapper);
    final LlmEngineAdapter llm = new LlmEngineAdapter(provider, mapper);
    final InputSnapshot input = new InputSnapshot("input", Instant.EPOCH,
            List.of(new Message("m1", 1, "customer", null, "我要退款")));

    com.fasterxml.jackson.databind.node.ObjectNode config(String... ids) {
        var config = mapper.createObjectNode().put("schemaVersion", "iqc-unified-tags-v1").put("conversationId", "c1");
        config.putObject("agentSnapshot");
        var tags = config.putArray("tags");
        for (String id : ids) tags.addObject().put("tagId", id).put("tagCode", "CODE" + id).put("ruleId", "r" + id)
                .put("ruleVersionNo", 1).put("expression", id.equals("1") ? "退款" : "投诉");
        return config;
    }
    UnifiedTagEngineAdapter adapter(EngineType type) { return new UnifiedTagEngineAdapter(mapper, regex, dls, llm, type); }
    EngineResult upstream(String id, ObservationStatus status, ExecutionStatus engineStatus) {
        return new EngineResult("up" + id, Operation.LABELING, "regex" + id, EngineType.REGEX, Integer.parseInt(id),
                Instant.EPOCH, Instant.EPOCH, engineStatus, config(id), null,
                List.of(new Observation(id, status, mapper.getNodeFactory().booleanNode(status == ObservationStatus.MATCHED), null, null, List.of())),
                List.of(), engineStatus == ExecutionStatus.FAILED ? new Failure("FAIL", "failed") : null);
    }
    @Test void regexReturnsOneStageWithExplicitMissAndLocatedEvidence() throws Exception {
        var output = adapter(EngineType.REGEX).execute(input, config("1", "2"));
        assertThat(output.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(output.observations()).extracting(Observation::status).containsExactly(ObservationStatus.MATCHED, ObservationStatus.NOT_MATCHED);
        assertThat(output.evidence()).hasSize(1);
        assertThat(mapper.readTree(output.rawOutput()).path("tagResults")).hasSize(2);
        verifyNoInteractions(provider);
    }
    @Test void onlyExplicitPriorMatchesAreRemovedAndRemainingTagsUseOneBatch() throws Exception {
        when(provider.evaluateTagBatch(any(), any(), anyString())).thenReturn(mapper.readTree("""
                {"results":[{"tagId":"2","status":"NOT_MATCHED"},{"tagId":"4","status":"MATCHED"},{"tagId":"5","status":"UNKNOWN"}]}
                """));
        var output = adapter(EngineType.LLM).execute(input, config("1", "2", "3", "4", "5"), List.of(
                upstream("1", ObservationStatus.MATCHED, ExecutionStatus.SUCCEEDED),
                upstream("2", ObservationStatus.NOT_MATCHED, ExecutionStatus.SUCCEEDED),
                upstream("3", ObservationStatus.MATCHED, ExecutionStatus.PARTIAL_SUCCESS),
                upstream("4", ObservationStatus.ERROR, ExecutionStatus.FAILED)));
        var request = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(provider, times(1)).evaluateTagBatch(request.capture(), any(), anyString());
        assertThat(request.getValue().path("tags").findValuesAsText("tagId")).containsExactly("2", "4", "5");
        assertThat(output.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(mapper.readTree(output.rawOutput()).at("/metadata/filteredTagCount").asInt()).isEqualTo(2);
    }
    @Test void emptyPendingSetSkipsModel() throws Exception {
        var output = adapter(EngineType.LLM).execute(input, config("1"), List.of(upstream("1", ObservationStatus.MATCHED, ExecutionStatus.SUCCEEDED)));
        assertThat(output.status()).isEqualTo(ExecutionStatus.SKIPPED);
        assertThat(mapper.readTree(output.rawOutput()).at("/metadata/modelCalled").asBoolean()).isFalse();
        verifyNoInteractions(provider);
    }
    @Test void twentyPendingTagsUseOneRequestAndOneStageResult() throws Exception {
        String[] ids = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(Integer::toString).toArray(String[]::new);
        var response = mapper.createObjectNode();
        response.putObject("metadata").put("model", "reported-model").put("inputTokens", 123).put("outputTokens", 45);
        var results = response.putArray("results");
        for (String id : ids) results.addObject().put("tagId", id).put("status", "NOT_MATCHED");
        when(provider.evaluateTagBatch(any(), any(), anyString())).thenReturn(response);

        var output = adapter(EngineType.LLM).execute(input, config(ids));

        var request = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(provider, times(1)).evaluateTagBatch(request.capture(), any(), anyString());
        assertThat(request.getValue().path("tags").findValuesAsText("tagId")).containsExactly(ids);
        assertThat(request.getValue().path("tags").findValuesAsText("prompt"))
                .containsExactly(java.util.stream.IntStream.rangeClosed(1, 20)
                        .mapToObj(i -> i == 1 ? "退款" : "投诉").toArray(String[]::new));
        assertThat(request.getValue().toString()).contains("我要退款");
        assertThat(output.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(output.observations()).extracting(Observation::targetId).containsExactly(ids);
        assertThat(output.observations()).allMatch(result -> result.status() == ObservationStatus.NOT_MATCHED);
        var metadata = mapper.readTree(output.rawOutput()).path("metadata");
        assertThat(metadata.at("/provider/inputTokens").asInt()).isEqualTo(123);
        assertThat(metadata.at("/provider/outputTokens").asInt()).isEqualTo(45);
        assertThat(metadata.at("/provider/model").asText()).isEqualTo("reported-model");
        assertThat(metadata.path("pendingTagCount").asInt()).isEqualTo(20);
    }
    @Test void malformedDuplicateMissingAndUnexpectedTagsNeverBecomeMisses() throws Exception {
        when(provider.evaluateTagBatch(any(), any(), anyString())).thenReturn(mapper.readTree("""
                {"results":[{"tagId":"1","status":"MATCHED"},{"tagId":"1","status":"NOT_MATCHED"},
                {"tagId":"2","status":"MATCHED"},{"tagId":"999","status":"MATCHED"}]}
                """));
        var output = adapter(EngineType.LLM).execute(input, config("1", "2", "3"));
        assertThat(output.status()).isEqualTo(ExecutionStatus.PARTIAL_SUCCESS);
        assertThat(output.observations()).extracting(Observation::status)
                .containsExactly(ObservationStatus.ERROR, ObservationStatus.MATCHED, ObservationStatus.ERROR);
        assertThat(output.observations()).extracting(Observation::targetId).doesNotContain("999");
    }
    @Test void requestTooLargeFailsWithoutCallingOrTruncating() throws Exception {
        var config = config("1"); ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("tags").get(0)).put("expression", "x".repeat(150001));
        var output = adapter(EngineType.LLM).execute(input, config);
        assertThat(output.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(output.observations().getFirst().status()).isEqualTo(ObservationStatus.ERROR);
        verifyNoInteractions(provider);
    }
    @Test void stagesAppendWithoutChangingRegexResultsAndProcessorDoesNotDecide() throws Exception {
        when(provider.evaluateTagBatch(any(), any(), anyString())).thenReturn(mapper.readTree("{\"results\":[{\"tagId\":\"2\",\"status\":\"MATCHED\"}]}"));
        var initial = new EngineExecutionResultSet(SCHEMA_VERSION, "set", "task", "exec", "c1", input, config(), List.of());
        var checkpoints = new java.util.ArrayList<EngineExecutionResultSet>();
        var result = new MultiEngineExecutor(Clock.systemUTC()).execute(initial, List.of(
                new MultiEngineExecutor.Step(Operation.LABELING, "regex", EngineType.REGEX, 1, config("1", "2"), adapter(EngineType.REGEX)),
                new MultiEngineExecutor.Step(Operation.LABELING, "llm", EngineType.LLM, 2, config("1", "2"), adapter(EngineType.LLM))), () -> false, checkpoints::add);
        assertThat(result.engines()).hasSize(2);
        assertThat(result.engines().getFirst()).isEqualTo(checkpoints.get(1).engines().getFirst());
        assertThat(result.engines().getFirst().observations()).extracting(Observation::status)
                .containsExactly(ObservationStatus.MATCHED, ObservationStatus.NOT_MATCHED);
        var processed = new UnifiedTaskResultProcessor(mapper).process(result, mapper.createObjectNode());
        assertThat(processed.sourceEngineResultIds()).hasSize(2);
        assertThat(processed.output().path("processingStatus").asText()).isEqualTo("PENDING_POLICY");
        assertThat(processed.output().has("scoring")).isFalse();
        assertThat(initial.engines()).isEmpty();
    }
    @Test void llmFirstEvaluatesEveryApplicableTagAndAnotherConversationHasNoUpstream() throws Exception {
        when(provider.evaluateTagBatch(any(), any(), anyString())).thenReturn(mapper.readTree("{\"results\":[{\"tagId\":\"1\",\"status\":\"NOT_MATCHED\"}]}"));
        adapter(EngineType.LLM).execute(input, config("1"), List.of());
        adapter(EngineType.LLM).execute(input, config("1"), List.of());
        verify(provider, times(2)).evaluateTagBatch(any(), any(), anyString());
    }
    @Test void processorRetainsBoundActionsAndNeverTreatsThemAsExecuted() throws Exception {
        var taskConfig = mapper.readTree("{\"unifiedLabels\":{\"labels\":[{\"id\":\"1\",\"actions\":[{\"actionId\":\"9007199254740993\",\"type\":\"DEDUCT_SCORE\",\"versionNo\":1}]}]}}");
        var set = new EngineExecutionResultSet(SCHEMA_VERSION, "set", "task", "exec", "c1", input, taskConfig, List.of());
        var output = new UnifiedTaskResultProcessor(mapper).process(set, mapper.createObjectNode()).output();
        assertThat(output.at("/actionStatuses/0/action/actionId").asText()).isEqualTo("9007199254740993");
        assertThat(output.at("/actionStatuses/0/status").asText()).isEqualTo("PENDING_POLICY");
        assertThat(output.path("labels")).isEmpty();assertThat(output.has("scoring")).isFalse();
        assertThat(set.taskConfiguration()).isEqualTo(taskConfig);
    }

}
