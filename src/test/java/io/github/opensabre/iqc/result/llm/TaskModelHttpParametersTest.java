package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Verifies overrides for the historical deployment model retained by IQC. */
class TaskModelHttpParametersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void defaultOpenAiTransportUsesCopiedTimeoutAndTemperature() throws Exception {
        var mapper = new ObjectMapper();
        var original = org.springframework.ai.openai.OpenAiChatModel.builder().options(
                org.springframework.ai.openai.OpenAiChatOptions.builder().apiKey("test-only").model("test-model")
                        .temperature(0.8).timeout(java.time.Duration.ofSeconds(60)).build()).build();
        var router = new SnapshotChatModelRouter(mock(io.github.opensabre.iqc.integration.iac.IacConsumerClient.class), original);
        var snapshot = mapper.readTree("{\"taskOverrides\":{\"temperature\":0,\"timeoutSeconds\":12}}");
        var configured = (org.springframework.ai.openai.OpenAiChatModel) router.taskDefault(snapshot);
        assertThat(configured.getOptions().getTimeout()).isEqualTo(java.time.Duration.ofSeconds(12));
        assertThat(configured.getOptions().getTemperature()).isZero();
        assertThat(original.getOptions().getTimeout()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(original.getOptions().getTemperature()).isEqualTo(0.8);
    }

    @Test
    void legacyDefaultModelSendsTaskOverridesWithoutChangingDeploymentOptions() throws Exception {
        try (var endpoint = new Endpoint(false, false)) {
            var original = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl(endpoint.url() + "/primary/v1").apiKey("isolated-test-key").model("legacy-model")
                    .temperature(0.8).timeout(Duration.ofSeconds(30)).maxRetries(0).build()).build();
            var snapshot = mapper.createObjectNode();
            snapshot.putObject("configJson").put("systemPrompt", "已发布行为");
            snapshot.putObject("taskOverrides").put("prompt", "本次退款要求").put("temperature", 0).put("timeoutSeconds", 1);
            var result = provider(new SnapshotChatModelRouter(mock(io.github.opensabre.iqc.integration.iac.IacConsumerClient.class), original))
                    .evaluate("申请退款", mapper.readTree("{\"id\":\"refund-rule\"}"), snapshot, "http-legacy");
            assertThat(result.supported()).as(result.reason()).isTrue();
            assertThat(endpoint.requests).hasSize(1);
            assertThat(endpoint.requests.getFirst().body.path("temperature").isNumber()).isTrue();
            assertThat(endpoint.requests.getFirst().body.path("temperature").asDouble()).isZero();
            assertThat(endpoint.requests.getFirst().body.path("messages").toString()).contains("本次退款要求");
            assertThat(original.getOptions().getTemperature()).isEqualTo(0.8);
            assertThat(original.getOptions().getTimeout()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    private SpringAiLlmQualityProvider provider(SnapshotChatModelRouter router) {
        var properties = new LlmQualityProperties();
        properties.setModel("isolated-model"); properties.setMaxAttempts(1);
        return new SpringAiLlmQualityProvider((org.springframework.ai.chat.model.ChatModel) null, router, mapper, mock(GovernanceRateLimiter.class),
                mock(UsageCounterRecorder.class), properties);
    }

    private record Request(String path, JsonNode body) { }

    /** Test-only model endpoint; never uses a live provider or application credentials. */
    private final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        private Endpoint(boolean failPrimary, boolean delayPrimary) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    requests.add(new Request(path, mapper.readTree(exchange.getRequestBody())));
                    boolean primary = path.contains("/primary/");
                    if (primary && delayPrimary) Thread.sleep(3000);
                    String reason = primary ? "primary reply" : "fallback reply";
                    String content = mapper.writeValueAsString(mapper.createObjectNode().put("hit", true).put("reason", reason));
                    var response = mapper.createObjectNode().put("id", "test-completion").put("object", "chat.completion").put("created", 1).put("model", "test-model");
                    var choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
                    choice.putObject("message").put("role", "assistant").put("content", content);
                    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(primary && failPrimary ? 503 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            server.start();
        }
        private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
