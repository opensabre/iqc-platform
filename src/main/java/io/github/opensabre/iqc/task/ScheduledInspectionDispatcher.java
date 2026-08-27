package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.result.InspectionExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "iqc.task", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ScheduledInspectionDispatcher {
    private final InspectionTaskService taskService;
    private final InspectionExecutionService executionService;

    @org.springframework.beans.factory.annotation.Value("${iqc.task.execution-timeout-minutes:30}")
    private long executionTimeoutMinutes;

    @Scheduled(fixedDelayString = "${iqc.task.scheduler-delay-ms:5000}")
    public void dispatchDueTasks() {
        int recovered = taskService.recoverStaleExecutions(java.time.Duration.ofMinutes(Math.max(1, executionTimeoutMinutes)));
        if (recovered > 0) log.warn("event=iqc_stale_execution_recovery recovered={}", recovered);
        for (var task : taskService.materializeDue(LocalDateTime.now())) {
            try {
                var queued = executionService.queueSystem(task.getId());
                executionService.executeAsync(queued.getId(), queued.getCurrentExecutionId());
            } catch (RuntimeException exception) {
                taskService.markDispatchFailed(task.getId());
                log.error("event=iqc_scheduled_dispatch taskId={} status=FAILED errorType={}", task.getId(), exception.getClass().getSimpleName(), exception);
            }
        }
    }
}
