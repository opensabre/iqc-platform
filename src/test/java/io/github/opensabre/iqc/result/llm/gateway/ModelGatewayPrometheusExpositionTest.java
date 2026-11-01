package io.github.opensabre.iqc.result.llm.gateway;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Original model invocation/admission/settlement exported using the production registry implementation. */
class ModelGatewayPrometheusExpositionTest {
    @Test void originalGatewayExportsDurationBucketsUsageAndUnknownOutcome() {
        var fixture = new ModelGatewayActiveInvocationTest(); fixture.setup();
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            meters.config().commonTags("application", "iqc-platform");
            var gateway = fixture.gateway(meters);
            assertEquals("answer", gateway.invoke(fixture.model, fixture.context, () -> "answer", value -> fixture.usage));
            var failure = new IllegalStateException("private supplier failure");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> gateway.invoke(fixture.model, fixture.context,
                    () -> { throw failure; }, value -> fixture.usage)));
            String text = meters.scrape();
            for (String name : List.of("iqc_model_gateway_active", "iqc_model_gateway_invocation_seconds_count",
                    "iqc_model_gateway_invocation_seconds_bucket", "iqc_model_gateway_tokens_total",
                    "iqc_model_gateway_usage_total", "iqc_model_gateway_admission_total", "iqc_model_gateway_settlement_total"))
                assertTrue(text.lines().anyMatch(line -> line.startsWith(name + "{") && line.contains("application=\"iqc-platform\"")), name);
            assertTrue(text.lines().anyMatch(line -> line.startsWith("iqc_model_gateway_invocation_seconds_count{") && line.contains("outcome=\"UNKNOWN\"")));
            assertTrue(text.lines().anyMatch(line -> line.startsWith("iqc_model_gateway_invocation_seconds_bucket{") && line.contains("le=\"+Inf\"")));
            assertFalse(text.contains("supplier.example")); assertFalse(text.contains("private supplier failure"));
            java.lang.ref.Reference.reachabilityFence(gateway);
        } finally { meters.close(); fixture.closeRegistry(); }
    }
}
