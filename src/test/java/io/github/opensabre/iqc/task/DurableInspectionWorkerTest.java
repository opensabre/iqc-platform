package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableInspectionWorkerTest {
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final InspectionTaskService taskService = mock(InspectionTaskService.class);
    private final InspectionExecutionService execution = mock(InspectionExecutionService.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final List<Runnable> submitted = new ArrayList<>();
    private final DurableInspectionWorker worker = new DurableInspectionWorker(tasks, taskService, execution, executor);
    private InspectionTask task(String id, String eid) {
        var task = new InspectionTask(); task.setId(id); task.setCurrentExecutionId(eid); task.setStatus("QUEUED"); return task;
    }
    @BeforeEach
    void executorBoundary() {
        when(executor.getThreadPoolExecutor()).thenReturn(pool);
        doAnswer(call -> { submitted.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        when(tasks.selectList(any())).thenReturn(List.of(task("task", "execution")));
        worker.validate();
    }
    @AfterEach
    void shutdown() { pool.shutdownNow(); }
    @Test
    void duplicateDiscoveryDoesNotSubmitSameLeaseUntilCompletion() {
        worker.discoverQueuedTasks(); worker.discoverQueuedTasks();
        assertThat(submitted).hasSize(1); verifyNoInteractions(execution);
        submitted.getFirst().run(); verify(execution).executePersisted("task", "execution");
        worker.discoverQueuedTasks(); assertThat(submitted).hasSize(2);
    }
    @Test
    void rejectedSubmissionRemainsDiscoverableWithoutChangingQueuedRow() {
        doThrow(new RejectedExecutionException("busy"))
                .doAnswer(call -> { submitted.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        worker.discoverQueuedTasks(); assertThat(submitted).isEmpty();
        worker.discoverQueuedTasks(); assertThat(submitted).hasSize(1);
        verify(tasks, never()).updateById(any(InspectionTask.class)); verifyNoInteractions(execution);
    }
    @Test
    void workerExceptionReleasesLocalLeaseForFutureDiscovery() {
        doThrow(new IllegalStateException("provider failed")).when(execution).executePersisted("task", "execution");
        worker.discoverQueuedTasks(); assertThatCode(() -> submitted.getFirst().run()).doesNotThrowAnyException();
        worker.discoverQueuedTasks(); assertThat(submitted).hasSize(2);
    }
    @Test
    void distinctExecutionIdCanBeDiscoveredWhileOldLocalLeaseExists() {
        worker.discoverQueuedTasks(); when(tasks.selectList(any())).thenReturn(List.of(task("task", "new-execution")));
        worker.discoverQueuedTasks(); assertThat(submitted).hasSize(2);
        submitted.getLast().run(); verify(execution).executePersisted("task", "new-execution");
    }
    @Test
    void localInFlightLimitBoundsSubmissionsAndStillRunsRecovery() {
        when(tasks.selectList(any())).thenReturn(java.util.stream.IntStream.range(0, 12).mapToObj(i -> task("task-" + i, "execution-" + i)).toList());
        worker.discoverQueuedTasks(); assertThat(submitted).hasSize(8);
        worker.discoverQueuedTasks(); assertThat(submitted).hasSize(8);
        verify(taskService, times(2)).recoverStaleExecutions(Duration.ofMinutes(30), false);
        verify(tasks, times(1)).selectList(any());
    }
    @Test
    void fullExecutorQueueDefersDiscoveryAndDoesNotConsumeTaskLease() {
        for (int i = 0; i < 16; i++) pool.getQueue().add(() -> {});
        worker.discoverQueuedTasks(); assertThat(submitted).isEmpty(); verify(tasks, never()).selectList(any());
        pool.getQueue().clear(); worker.discoverQueuedTasks(); assertThat(submitted).hasSize(1);
    }
}
