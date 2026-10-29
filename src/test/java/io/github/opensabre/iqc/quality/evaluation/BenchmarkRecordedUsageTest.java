package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.result.llm.gateway.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Validated sample provenance determines accounting scope, independently of the report execution. */
class BenchmarkRecordedUsageTest {
    private final PipelineEvaluationService evaluations = mock(PipelineEvaluationService.class);
    private final GatewayCallService calls = mock(GatewayCallService.class);
    private final SensitiveReadAccessService sensitive = mock(SensitiveReadAccessService.class);
    private final BenchmarkService service = new BenchmarkService(evaluations, null, null, null,
            new ObjectMapper(), calls, sensitive);

    private PipelineEvaluationService.Report report(Map<String, String> sources, int expected) {
        var report = mock(PipelineEvaluationService.Report.class);
        when(report.sourceExecutionIds()).thenReturn(sources);
        when(report.expectedSamples()).thenReturn(expected);
        when(report.executionId()).thenReturn("current-report-execution");
        var context = mock(PipelineEvaluationService.Context.class);
        var prepared = mock(PipelineEvaluationService.Prepared.class);
        var dataset = mock(DatasetSnapshot.class);
        var members = new ArrayList<DatasetSnapshot.Member>();
        for (String id : List.of("sample-a", "sample-b")) {
            var member = mock(DatasetSnapshot.Member.class);
            var gold = mock(GoldConversation.class);
            when(member.sampleId()).thenReturn(id);
            when(member.gold()).thenReturn(gold);
            when(gold.conversationId()).thenReturn("conversation-" + id);
            members.add(member);
        }
        when(dataset.members()).thenReturn(members);
        when(prepared.dataset()).thenReturn(dataset);
        when(context.prepared()).thenReturn(prepared);
        when(evaluations.context("task")).thenReturn(context);
        return report;
    }
    private BenchmarkService.UsageSide usage(PipelineEvaluationService.Report report) {
        return ReflectionTestUtils.invokeMethod(service, "usageSide", "task", report);
    }
    @Test void mixedHistoricalSourcesUseExactTaskExecutionConversationTriples() {
        var report = report(Map.of("sample-a", "source-old", "sample-b", "source-new"), 2);
        var recorded = new GatewayCallService.RunUsage(new GatewayCallService.CostSummary("3", "1", "2", List.of()),
                new GatewayCallService.CallLatencySummary("3", "2", "1", "12"));
        var expected = List.of(new GatewayCallMapper.RunReference("task", "source-old", "conversation-sample-a"),
                new GatewayCallMapper.RunReference("task", "source-new", "conversation-sample-b"));
        when(calls.taskRunUsage(expected)).thenReturn(recorded);
        var result = usage(report);
        assertEquals(2, result.boundSamples());
        assertEquals(0, result.unboundSamples());
        assertSame(recorded, result.recorded());
        assertEquals("2", result.recorded().costs().unknownCount());
        verify(calls).taskRunUsage(expected);
    }
    @Test void missingSamplesStayUnboundWithoutWideningToCurrentExecution() {
        var result = usage(report(Map.of("sample-b", "source-new"), 2));
        assertEquals(2, result.expectedSamples());
        assertEquals(1, result.boundSamples());
        assertEquals(1, result.unboundSamples());
        verify(calls).taskRunUsage(List.of(new GatewayCallMapper.RunReference("task", "source-new", "conversation-sample-b")));
    }
    @Test void foreignSamplesOrChangedDenominatorRejectBeforeAccountingRead() {
        assertThrows(RuntimeException.class, () -> usage(report(Map.of("foreign", "source"), 2)));
        assertThrows(RuntimeException.class, () -> usage(report(Map.of("sample-a", "source"), 3)));
        verifyNoInteractions(calls);
    }
    @Test void usageAuthorizationRunsBeforePairOrReportReads() {
        var resources = Set.of("iqc:benchmark:report:view", "iqc:task:model-calls:view");
        when(sensitive.current(resources)).thenThrow(new IllegalStateException("denied"));
        assertThrows(IllegalStateException.class, () -> service.report("task", "left", "right", true));
        verify(sensitive).current(resources);
        verifyNoInteractions(evaluations, calls);
    }
}
