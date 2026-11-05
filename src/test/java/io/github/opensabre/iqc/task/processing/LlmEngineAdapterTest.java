package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.assertThat;

class LlmEngineAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void preservesAllResponsesAndActualConversationIdentity() throws Exception {
        var provider = new LlmQualityProvider() {
            public LlmEvaluation evaluate(String content, com.fasterxml.jackson.databind.JsonNode rule, String id) {
                throw new AssertionError("must use conversation trace");
            }
            @Override public LlmEvaluation evaluateConversationTraced(
                    List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
                    com.fasterxml.jackson.databind.JsonNode rule, com.fasterxml.jackson.databind.JsonNode agent,
                    String id, java.util.function.Consumer<String> raw) {
                assertThat(messages.getFirst().getConversationId()).isEqualTo("conversation-1");
                raw.accept("bad first response"); raw.accept(" {\"hit\":true,\"reason\":\"matched\"} ");
                return new LlmEvaluation(true, true, "matched");
            }
        };
        var output = new LlmEngineAdapter(provider, mapper).execute(input(), configuration());
        assertThat(mapper.readTree(output.rawOutput()).get(0).asText()).isEqualTo("bad first response");
        assertThat(mapper.readTree(output.rawOutput()).get(1).asText()).startsWith(" ").endsWith(" ");
        assertThat(output.observations().getFirst().value().booleanValue()).isTrue();
        assertThat(output.failure()).isNull();
    }

    @Test void retainsRawResponseWhenProviderThrowsAndRejectsUnsupportedAdapters() throws Exception {
        var provider = new LlmQualityProvider() {
            public LlmEvaluation evaluate(String content, com.fasterxml.jackson.databind.JsonNode rule, String id) {
                return new LlmEvaluation(true, true, "legacy");
            }
            @Override public LlmEvaluation evaluateConversationTraced(
                    List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
                    com.fasterxml.jackson.databind.JsonNode rule, com.fasterxml.jackson.databind.JsonNode agent,
                    String id, java.util.function.Consumer<String> raw) {
                raw.accept("unparseable"); throw new IllegalArgumentException("parse failed");
            }
        };
        var output = new LlmEngineAdapter(provider, mapper).execute(input(), configuration());
        assertThat(output.rawOutput()).isEqualTo("[\"unparseable\"]");
        assertThat(output.failure().code()).isEqualTo("LLM_ADAPTER_FAILED");
        LlmQualityProvider legacy = (content, rule, id) -> new LlmQualityProvider.LlmEvaluation(true, true, "legacy");
        var unsupported = new LlmEngineAdapter(legacy, mapper).execute(input(), configuration());
        assertThat(unsupported.failure().code()).isEqualTo("LLM_EVALUATION_FAILED");
        assertThat(unsupported.rawOutput()).isNull();
    }

    private InputSnapshot input() {
        return new InputSnapshot("snapshot-1", Instant.EPOCH,
                List.of(new Message("m1", 1, "CUSTOMER", null, "申请退款")));
    }
    private com.fasterxml.jackson.databind.JsonNode configuration() throws Exception {
        return mapper.readTree("{\"targetId\":\"t1\",\"conversationId\":\"conversation-1\",\"rule\":{},\"agentSnapshot\":{}}");
    }
}
