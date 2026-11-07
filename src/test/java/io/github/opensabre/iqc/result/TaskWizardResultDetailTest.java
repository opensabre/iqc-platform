package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.dao.*;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.processing.EngineResultSetStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import io.github.opensabre.iqc.task.processing.EngineExecutionResultSet;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskWizardResultDetailTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final ConversationInspectionResultMapper conversations = mock(ConversationInspectionResultMapper.class);
    private final EngineResultSetStore store = spy(new EngineResultSetStore(null, mapper));
    private final HierarchicalResultService service = new HierarchicalResultService(conversations,
            mock(RuleInspectionResultMapper.class), mock(InspectionEvidenceMapper.class), mapper,
            mock(io.github.opensabre.iqc.label.LabelResultService.class), tasks,
            mock(InspectionResultMapper.class), mock(TaskItemMapper.class), store);
    private InspectionTask task;
    private EngineExecutionResultSet source;

    @BeforeEach void setup() throws Exception {
        task = new InspectionTask(); task.setId("t1"); task.setCurrentExecutionId("ex2");
        task.setRuleSnapshotJson("{\"schemaVersion\":\"iqc-task-wizard-v1\"}");
        when(tasks.selectById("t1")).thenReturn(task);
        source = new EngineExecutionResultSet(SCHEMA_VERSION, "rs1", "t1", "ex2", "c1",
                new InputSnapshot("input1", Instant.parse("2026-11-06T00:00:00Z"),
                        List.of(new Message("m1", 1, "CUSTOMER", "00:01", "退款"))),
                mapper.readTree(task.getRuleSnapshotJson()), List.of());
        doReturn(Optional.of(source)).when(store).find("ex2", "c1");
    }

    @Test void checkpointWithoutProcessedResultRetainsFrozenInputAndExecution() {
        var result = service.hierarchy("t1", "c1");
        assertThat(result.executionId()).isEqualTo("ex2");
        assertThat(result.conversation()).isNull();
        assertThat(result.processedResult()).isNull();
        assertThat(result.engineResultSet().path("input").path("messages").get(0).path("content").asText()).isEqualTo("退款");
        assertThat(result.engineResultSet().path("input").hasNonNull("capturedAt")).isTrue();
        verify(conversations, never()).selectLatestForTaskConversation(any(), any());
    }

    @Test void failedEngineAndFollowingEvidenceAreReturnedWithoutPickingAWinner() {
        var now = source.input().capturedAt();
        var failed = new EngineResult("r1", Operation.INSPECTION, "llm", EngineType.LLM, 1, now, now,
                ExecutionStatus.FAILED, mapper.createObjectNode(), "invalid response", List.of(), List.of(),
                new Failure("INVALID", "无法解析响应"));
        var succeeded = new EngineResult("r2", Operation.INSPECTION, "regex", EngineType.REGEX, 2, now, now,
                ExecutionStatus.SUCCEEDED, mapper.createObjectNode(), "matched", List.of(),
                List.of(new Evidence("proof", "m1", 0, 2, "退款")), null);
        var full = new EngineExecutionResultSet(SCHEMA_VERSION, "rs1", "t1", "ex2", "c1", source.input(),
                source.taskConfiguration(), List.of(failed, succeeded));
        doReturn(Optional.of(full)).when(store).find("ex2", "c1");
        var detail = service.hierarchy("t1", "c1");
        var engines = detail.engineResultSet().path("engines");
        assertThat(engines.size()).isEqualTo(2);
        assertThat(engines.get(0).path("rawOutput").asText()).isEqualTo("invalid response");
        assertThat(engines.get(0).path("failure").path("message").asText()).isEqualTo("无法解析响应");
        assertThat(engines.get(1).path("evidence").get(0).path("quote").asText()).isEqualTo("退款");
        assertThat(detail.processedResult()).isNull();
    }

    @Test void processedResultMustReferenceExactEvidenceSet() throws Exception {
        var canonical = new ConversationInspectionResult();
        canonical.setBusinessItemResultsJson("""
                {"sourceResultSetId":"rs1","sourceEngineResultIds":[],
                "output":{"schemaVersion":"iqc-task-processed-results-v1","labels":[]}}
                """);
        when(conversations.selectForTaskExecutionConversation("t1", "ex2", "c1")).thenReturn(canonical);
        assertThat(service.hierarchy("t1", "c1").processedResult().path("sourceResultSetId").asText()).isEqualTo("rs1");
        canonical.setBusinessItemResultsJson(canonical.getBusinessItemResultsJson().replace("rs1", "other"));
        assertThatThrownBy(() -> service.hierarchy("t1", "c1")).hasMessageContaining("处理结果与引擎证据不一致");
    }

    @Test void explicitHistoricalExecutionNeverFallsBackToLatest() {
        doReturn(Optional.empty()).when(store).find("old", "c1");
        assertThat(service.hierarchy("t1", "c1", "old")).isNull();
        verify(conversations, never()).selectLatestForTaskConversation(any(), any());
    }

    @Test void rejectsEvidenceFromAnotherTask() {
        var other = new EngineExecutionResultSet(SCHEMA_VERSION, "rs1", "other", "ex2", "c1",
                source.input(), source.taskConfiguration(), List.of());
        doReturn(Optional.of(other)).when(store).find("ex2", "c1");
        assertThatThrownBy(() -> service.hierarchy("t1", "c1")).hasMessageContaining("引擎证据与任务快照不一致");
    }

    @Test void missingRawEvidenceCannotMasqueradeAsCompletedResult() {
        doReturn(Optional.empty()).when(store).find("ex2", "c1");
        when(conversations.selectForTaskExecutionConversation("t1", "ex2", "c1"))
                .thenReturn(new ConversationInspectionResult());
        assertThatThrownBy(() -> service.hierarchy("t1", "c1")).hasMessageContaining("引擎证据缺失");
    }
}
