package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.result.llm.gateway.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Loopback transport plus real gateway; only admission persistence and governance are controlled. */
class HttpLlmGatewayTransportTest {
    private final ObjectMapper json = new ObjectMapper();

    private void call(String deployment, boolean wrongHandle, boolean settlementFails) throws Exception {
        var requests = new AtomicInteger();
        var observed = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            observed.set(json.readTree(exchange.getRequestBody()));
            byte[] body = ("{\"model\":\"served-model-v2\",\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":2}},"
                    + "\"choices\":[{\"message\":{\"content\":\"{\\\"hit\\\":true,\\\"reason\\\":\\\"measured\\\",\\\"confidence\\\":0.75}\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var properties = new LlmQualityProperties();
            properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setModel("requested-model");
            properties.setDeploymentRef(deployment);
            properties.setApiKey("test-only-secret");
            properties.setMaxAttempts(3);
            properties.setRetryBackoffMillis(0);
            var calls = mock(GatewayCallService.class);
            var limiter = mock(GovernanceRateLimiter.class);
            when(limiter.check(any())).thenReturn(new io.github.opensabre.governance.ratelimit.RateLimitDecision(
                    true, 60, 0, 60, null, "isolated-test"));
            when(calls.reserve(any(), any(), anyInt())).thenAnswer(invocation -> {
                GatewayCallService.ModelIdentity identity = invocation.getArgument(0);
                return new GatewayCallService.Handle("call-1", wrongHandle ? "f".repeat(64) : identity.key());
            });
            if (settlementFails) doThrow(new IllegalStateException("store unavailable")).when(calls).confirmed(any(), any());
            var gateway = new ModelGateway(calls, limiter, properties);
            var provider = new HttpLlmQualityProvider(json, limiter, mock(UsageCounterRecorder.class), properties, gateway);
            var evaluation = provider.evaluate("客户手机号13812345678", json.createObjectNode().put("id", "rule"), "record");
            assertEquals(1, requests.get(), "a responded request must not be retried for settlement repair");
            assertEquals("Bearer test-only-secret", authorization.get());
            assertEquals("requested-model", observed.get().path("model").asText());
            assertFalse(observed.get().toString().contains("13812345678"));
            assertTrue(observed.get().toString().contains("[手机号]"));
            var model = ArgumentCaptor.forClass(GatewayCallService.ModelIdentity.class);
            verify(calls).reserve(model.capture(), any(), anyInt());
            var usage = ArgumentCaptor.forClass(GatewayCallService.Usage.class);
            verify(calls).confirmed(any(), usage.capture());
            assertEquals("COMPLETE", usage.getValue().status());
            assertEquals(8L, usage.getValue().inputTokens());
            assertEquals(4L, usage.getValue().outputTokens());
            assertEquals(12L, usage.getValue().totalTokens());
            assertEquals(2L, usage.getValue().cacheReadInputTokens());
            assertEquals("served-model-v2", usage.getValue().responseModelId());
            if (settlementFails) {
                assertFalse(evaluation.supported());
                assertNull(evaluation.invocationFingerprint());
                return;
            }
            assertTrue(evaluation.supported());
            assertTrue(evaluation.hit());
            if (wrongHandle) assertNull(evaluation.invocationFingerprint());
            else {
                var fingerprint = evaluation.invocationFingerprint();
                assertNotNull(fingerprint);
                assertEquals(model.getValue().key(), fingerprint.modelKey());
                assertEquals(deployment, fingerprint.deploymentRef());
                var input = new LinkedHashMap<String, Object>();
                input.put("schemaVersion", "iqc-http-invocation-input-v1");
                input.put("modelKey", fingerprint.modelKey());
                input.put("deploymentRef", deployment);
                input.put("requestPath", "/v1/chat/completions");
                input.put("request", observed.get());
                assertEquals(SelectorAssetService.hash(json.valueToTree(input).toString()), fingerprint.inputHash());
            }
        } finally { server.stop(0); }
    }
    @Test void realRequestBindsDeploymentAndReportedUsageToOriginalGatewayCall() throws Exception {
        call("deployment-v2", false, false);
    }
    @Test void undeclaredDeploymentAndMismatchedHandlesCannotInventBinding() throws Exception {
        call(null, false, false);
        call("deployment-v2", true, false);
    }
    @Test void failedSettlementNeverRepeatsSuccessfulSupplierRequest() throws Exception {
        call("deployment-v2", false, true);
    }
}
