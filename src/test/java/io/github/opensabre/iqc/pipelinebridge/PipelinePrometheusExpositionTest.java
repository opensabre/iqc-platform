package io.github.opensabre.iqc.pipelinebridge;

import io.github.opensabre.iqc.pipeline.*;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Original evaluator/runtime metrics through the real Prometheus exporter, not hand-built series. */
class PipelinePrometheusExpositionTest {
    @Test void originalRuntimeExportsQueryableHistogramsAndProductionLabels() {
        var fixture = new PipelineMonitoringTest(); fixture.setup();
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var runtime = new PipelineRuntime(List.of(fixture.adapter(context -> fixture.success())), 1, 2)) {
            meters.config().commonTags("application", "iqc-platform");
            var evaluator = fixture.evaluator(runtime, meters);
            var release = fixture.release(PipelineDefinition.SCHEMA, List.of(fixture.node("private_node", "RULE", 0,
                    PipelineDefinition.OnError.CONTINUE, null, true, false)));
            fixture.evaluate(evaluator, release, PipelineRuntime.Mode.PRODUCTION);
            String text = meters.scrape();
            for (String name : List.of("iqc_pipeline_graph_duration_seconds_count", "iqc_pipeline_graph_duration_seconds_bucket",
                    "iqc_pipeline_node_duration_seconds_count", "iqc_pipeline_node_duration_seconds_bucket",
                    "iqc_pipeline_node_observations_total", "iqc_pipeline_graph_active")) {
                assertTrue(text.lines().anyMatch(line -> line.startsWith(name + "{")
                        && line.contains("application=\"iqc-platform\"") && line.contains("mode=\"PRODUCTION\"")), name);
            }
            for (String name : List.of("iqc_pipeline_executor_active", "iqc_pipeline_executor_queued"))
                assertTrue(text.lines().anyMatch(line -> line.startsWith(name + "{")), name);
            assertTrue(text.lines().anyMatch(line -> line.startsWith("iqc_pipeline_graph_duration_seconds_bucket{") && line.contains("le=\"+Inf\"")));
            assertFalse(text.contains("private-")); assertFalse(text.contains("private_node")); assertFalse(text.contains("PRIVATE_NODE"));
            java.lang.ref.Reference.reachabilityFence(evaluator);
        } finally { meters.close(); }
    }
}
