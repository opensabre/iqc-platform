package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cached durable-worker gauges through real exporter; scrape must never perform database sampling. */
class DurableTaskPrometheusExpositionTest {
    @Test @SuppressWarnings("unchecked") void cachedSamplesExportSecondsAndKeepUnknownCountsAsNan() {
        var tasks = mock(InspectionTaskMapper.class);
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            meters.config().commonTags("application", "iqc-platform");
            ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(meters);
            var worker = new DurableInspectionWorker(tasks, mock(InspectionTaskService.class),
                    mock(InspectionExecutionService.class), mock(ThreadPoolTaskExecutor.class), provider); worker.validate();
            assertEquals(0, value(meters.scrape(), "iqc_task_metrics_sample_success"));
            verifyNoInteractions(tasks);
            when(tasks.operationalCounts()).thenReturn(List.of(new InspectionTaskMapper.OperationalCount("PRODUCTION", "TASK_STATE", "QUEUED", 7)));
            worker.sampleOperationalMetrics(); clearInvocations(tasks);
            for (int i = 0; i < 3; i++) {
                String text = meters.scrape();
                assertEquals(1, value(text, "iqc_task_metrics_sample_success"));
                assertTrue(Double.isFinite(value(text, "iqc_task_metrics_sample_age_seconds")));
                assertTrue(text.lines().anyMatch(line -> line.startsWith("iqc_task_persisted_state{")
                        && line.contains("mode=\"PRODUCTION\"") && line.contains("status=\"QUEUED\"") && line.endsWith(" 7.0")));
                for (String name : List.of("iqc_task_execution_completed_window", "iqc_task_execution_unmeasured"))
                    assertTrue(text.lines().anyMatch(line -> line.startsWith(name + "{")), name);
            }
            verifyNoInteractions(tasks);
            when(tasks.operationalCounts()).thenThrow(new IllegalStateException("private database failure"));
            worker.sampleOperationalMetrics(); clearInvocations(tasks);
            String failed = meters.scrape(); assertEquals(0, value(failed, "iqc_task_metrics_sample_success"));
            assertTrue(failed.lines().anyMatch(line -> line.startsWith("iqc_task_persisted_state{") && line.endsWith(" NaN")));
            assertFalse(failed.contains("private database failure")); verifyNoInteractions(tasks);
            java.lang.ref.Reference.reachabilityFence(worker);
        } finally { meters.close(); }
    }
    private static double value(String text, String name) {
        String line = text.lines().filter(value -> value.startsWith(name + "{")).findFirst().orElseThrow();
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }
}
