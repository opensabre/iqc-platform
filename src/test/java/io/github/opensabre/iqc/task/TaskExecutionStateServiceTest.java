package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskExecutionStateServiceTest {
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final TaskExecutionStateService states = new TaskExecutionStateService(tasks, executions);
    private final InspectionTask task = new InspectionTask();
    private final TaskExecution execution = new TaskExecution();
    @BeforeEach
    void queued() {
        for (Class<?> type : List.of(InspectionTask.class, TaskExecution.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getSimpleName()), type);
        task.setId("task"); task.setCurrentExecutionId("current"); task.setStatus("QUEUED"); task.setTotalMessages(10);
        execution.setId("current"); execution.setTaskId("task"); execution.setStatus("QUEUED");
        when(tasks.selectOne(any())).thenReturn(task); when(executions.selectOne(any())).thenReturn(execution);
        when(tasks.update(isNull(), any())).thenReturn(1); when(executions.update(isNull(), any())).thenReturn(1);
        when(tasks.updateById(any(InspectionTask.class))).thenReturn(1);
        when(executions.updateById(any(TaskExecution.class))).thenReturn(1);
    }
    @Test
    void currentQueueClaimLocksTaskBeforeExecutionAndWritesBothStates() {
        assertThat(states.claim("task", "current")).isTrue();
        var order = inOrder(tasks, executions);
        order.verify(tasks).selectOne(any()); order.verify(executions).selectOne(any());
        order.verify(tasks).update(isNull(), any()); order.verify(executions).update(isNull(), any());
        task.setStatus("RUNNING"); execution.setStatus("RUNNING");
        assertThat(states.claim("task", "current")).isFalse();
        verify(tasks, times(1)).update(isNull(), any());
    }
    @Test
    void staleExecutionCannotClaimFinishOrFailCurrentTask() {
        assertThat(states.claim("task", "old")).isFalse();
        assertThat(states.finish("task", "old", 10, 0)).isSameAs(task);
        assertThat(states.fail("task", "old", false, "old error")).isFalse();
        assertThat(task.getStatus()).isEqualTo("QUEUED"); verifyNoInteractions(executions);
        verify(tasks, never()).updateById(any(InspectionTask.class)); verify(tasks, never()).update(isNull(), any());
    }
    @Test
    void finishPreservesPauseAndCancellationRatherThanReportingSuccess() {
        execution.setStatus("RUNNING"); task.setStatus("PAUSE_REQUESTED");
        states.finish("task", "current", 3, 0);
        assertThat(task.getStatus()).isEqualTo("PAUSED"); assertThat(execution.getStatus()).isEqualTo("PAUSED");
        task.setStatus("CANCEL_REQUESTED"); execution.setStatus("RUNNING");
        states.finish("task", "current", 4, 1);
        assertThat(task.getStatus()).isEqualTo("CANCELLED"); assertThat(execution.getStatus()).isEqualTo("CANCELLED");
        assertThat(task.getProcessedMessages()).isEqualTo(4); assertThat(execution.getFailedMessages()).isEqualTo(1);
    }
    @Test
    void terminalExecutionCannotBeOverwrittenByLateFinishOrFailure() {
        task.setStatus("SUCCEEDED"); execution.setStatus("SUCCEEDED");
        states.finish("task", "current", 0, 10);
        assertThat(states.fail("task", "current", true, "late")).isFalse();
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        verify(tasks, never()).updateById(any(InspectionTask.class)); verify(executions, never()).updateById(any(TaskExecution.class));
    }
    @Test
    void workerFailureRespectsPauseRequestAndRetainsFailureReason() {
        task.setStatus("PAUSE_REQUESTED"); execution.setStatus("RUNNING");
        assertThat(states.fail("task", "current", true, "provider failed")).isTrue();
        assertThat(task.getStatus()).isEqualTo("PAUSED"); assertThat(execution.getStatus()).isEqualTo("PAUSED");
        assertThat(execution.getErrorMessage()).isEqualTo("provider failed");
    }
    @Test
    void lostTaskClaimDoesNotWriteExecutionAndLostExecutionWriteRaises() {
        when(tasks.update(isNull(), any())).thenReturn(0);
        assertThat(states.claim("task", "current")).isFalse(); verify(executions, never()).update(isNull(), any());
        when(tasks.update(isNull(), any())).thenReturn(1); when(executions.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> states.claim("task", "current")).hasMessageContaining("lost its locked row");
    }
    @Test
    void queuedFailureCountsOnlyRemainingMessages() {
        task.setProcessedMessages(4);
        assertThat(states.fail("task", "current", false, "start failed")).isTrue();
        assertThat(task.getStatus()).isEqualTo("FAILED"); assertThat(task.getFailedMessages()).isEqualTo(6);
        assertThat(execution.getStatus()).isEqualTo("FAILED");
    }
}
