package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelCandidateService;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.scheme.SchemeDependencyResolver;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A dependency disabled after task creation must prevent further work without rewriting task snapshots. */
class SchemeExecutionGuardTest {
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final TaskItemMapper items = mock(TaskItemMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final InspectionResultMapper results = mock(InspectionResultMapper.class);
    private final LlmQualityProvider llm = mock(LlmQualityProvider.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final SchemeDependencyResolver guard = mock(SchemeDependencyResolver.class);
    private final InspectionExecutionService service = new InspectionExecutionService(tasks, mock(ConversationMapper.class), messages,
            results, new ObjectMapper(), executions, items, scope, llm, mock(UsageCounterRecorder.class),
            mock(HierarchicalResultService.class), mock(LabelCandidateService.class), guard);
    private InspectionTask task;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "scheme-execution-guard"), InspectionTask.class);
        task = new InspectionTask(); task.setId("t1"); task.setCurrentExecutionId("e1");
        task.setRuleSnapshotJson("{\"schemeSnapshot\":{\"frozen\":true}}");
        task.setTotalMessages(4); task.setProcessedMessages(1);
        when(tasks.selectById("t1")).thenReturn(task);
        when(scope.canView(any(), any())).thenReturn(true);
        doThrow(IqcException.invalidState("规则或模型已停用")).when(guard).validateTaskDependencies(any());
    }

    @ParameterizedTest
    @ValueSource(strings={"CREATED", "FAILED", "PARTIAL_FAILED"})
    void queueAndRetryRejectDisabledDependenciesBeforeCreatingAnAttempt(String status) {
        task.setStatus(status);
        assertThatThrownBy(() -> service.queue("t1")).hasMessageContaining("停用");
        assertThat(task.getStatus()).isEqualTo(status);
        verify(tasks, never()).update(isNull(), any());
        verifyNoInteractions(executions, items, messages, results, llm);
    }

    @Test
    void schedulerDoesNotBypassDependencyChecks() {
        task.setStatus("CREATED");
        assertThatThrownBy(() -> service.queueSystem("t1")).hasMessageContaining("停用");
        verifyNoInteractions(executions, items, messages, results, llm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PAUSED"})
    void missingRouteStageRejectsQueueOrResumeBeforeAnyAttemptMutation(String status) {
        task.setStatus(status);
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"kind":"DRAFT_TRIAL","schemeId":"scheme","release":{
                "definition":{"schemaVersion":"iqc-scheme-v2-item-routes-v1","items":[{
                "itemCode":"check","name":"检查","rule":{"id":"r1","versionNo":1},"hitMeaning":"VIOLATION",
                "execution":{"route":"RULE_ONLY"}}],"scoring":{"version":"iqc-score-v2","mode":"DEDUCTION",
                "baseScore":100,"passingScore":60,"items":[]}},"dependencies":{"rules":[]}}},"rules":[]}
                """);
        assertThatThrownBy(() -> {
            if ("PAUSED".equals(status)) service.resume("t1"); else service.queue("t1");
        }).hasMessageContaining("缺少检测阶段");
        assertThat(task.getStatus()).isEqualTo(status);
        assertThat(task.getCurrentExecutionId()).isEqualTo("e1");
        verify(tasks, never()).update(isNull(), any());
        verifyNoInteractions(guard, executions, items, messages, results, llm);
    }

    @Test
    void resumePreservesPausedStateAndPreviousExecution() {
        task.setStatus("PAUSED");
        assertThatThrownBy(() -> service.resume("t1")).hasMessageContaining("停用");
        assertThat(task.getStatus()).isEqualTo("PAUSED");
        assertThat(task.getCurrentExecutionId()).isEqualTo("e1");
        verifyNoInteractions(executions, items, messages, results, llm);
    }

    @Test
    void queueRejectsJointTaskWithMissingFrozenLabelScopeBeforeCreatingAttempt() {
        task.setStatus("CREATED");
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"release":{"definition":{"labels":[{"id":"l1","versionNo":1}]},
                "dependencies":{"labels":{"schemaVersion":"2.0","labels":[{"id":"l1","versionNo":1}]},
                "rules":[{"id":"r1","versionNo":1}]} }},"rules":[{"id":"r1","versionNo":1}]}
                """);
        assertThatThrownBy(() -> service.queue("t1")).hasMessageContaining("标签快照");
        verifyNoInteractions(guard, executions, items, messages, results, llm);
    }

    @Test
    void assetDisabledWhileWaitingFailsOwnedAttemptBeforeCallingModelOrProcessingMessages() {
        task.setStatus("QUEUED");
        var execution = new TaskExecution(); execution.setId("e1"); execution.setStatus("QUEUED");
        when(executions.selectById("e1")).thenReturn(execution);
        when(tasks.update(isNull(), any())).thenReturn(1);
        String frozen = task.getRuleSnapshotJson();
        service.executeAsync("t1", "e1");
        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getFailedMessages()).isEqualTo(3);
        assertThat(task.getRuleSnapshotJson()).isEqualTo(frozen);
        assertThat(execution.getStatus()).isEqualTo("FAILED");
        assertThat(execution.getErrorMessage()).contains("停用");
        verifyNoInteractions(items, messages, results, llm);
    }
}
