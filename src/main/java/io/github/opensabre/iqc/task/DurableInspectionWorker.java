package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** The database QUEUED row is the queue; executor submission is a replaceable wake-up hint. */
@Component @Slf4j
@ConditionalOnProperty(prefix="iqc.task",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class DurableInspectionWorker {
    private final InspectionTaskMapper tasks;
    private final InspectionTaskService taskService;
    private final InspectionExecutionService execution;
    private final ThreadPoolTaskExecutor executor;
    private record Lease(String taskId,String executionId) { }
    private final Set<Lease> inFlight=ConcurrentHashMap.newKeySet();
    @Value("${iqc.task.worker-max-in-flight:8}") private int maximumInFlight=8;
    @Value("${iqc.task.execution-timeout-minutes:30}") private long timeoutMinutes=30;
    public DurableInspectionWorker(InspectionTaskMapper tasks,InspectionTaskService taskService,InspectionExecutionService execution,
                                   @Qualifier("iqcTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.tasks=tasks;this.taskService=taskService;this.execution=execution;this.executor=executor;
    }
    @jakarta.annotation.PostConstruct
    public void validate() {
        if(maximumInFlight<1 || maximumInFlight>64 || timeoutMinutes<1)
            throw new IllegalArgumentException("worker-max-in-flight must be 1..64; timeout must be positive");
    }
    @Scheduled(fixedDelayString="${iqc.task.worker-delay-ms:2000}")
    public synchronized void discoverQueuedTasks() {
        int recovered=taskService.recoverStaleExecutions(Duration.ofMinutes(timeoutMinutes),false);
        if(recovered>0)log.warn("event=iqc_worker_stale_recovery recovered={}",recovered);
        if(inFlight.size()>=maximumInFlight || executor.getThreadPoolExecutor().getQueue().remainingCapacity()==0)return;
        var queued=tasks.selectList(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getStatus,"QUEUED")
                .isNotNull(InspectionTask::getCurrentExecutionId).orderByDesc(InspectionTask::getQueuePriority)
                .orderByAsc(InspectionTask::getCreatedTime).orderByAsc(InspectionTask::getId).last("LIMIT 100"));
        for(var task:queued) {
            if(inFlight.size()>=maximumInFlight)break;
            var lease=new Lease(task.getId(),task.getCurrentExecutionId());
            if(!inFlight.add(lease))continue;
            try {
                executor.execute(()->{
                    try{execution.executePersisted(lease.taskId(),lease.executionId());}
                    catch(RuntimeException failure){log.error("event=iqc_worker_dispatch taskId={} executionId={} errorType={}",
                            lease.taskId(),lease.executionId(),failure.getClass().getSimpleName(),failure);}
                    finally{inFlight.remove(lease);}
                });
            } catch(RejectedExecutionException busy) {
                inFlight.remove(lease); // Keep the persisted QUEUED state; the next discovery may submit it.
                log.info("event=iqc_worker_backpressure taskId={} executionId={}",lease.taskId(),lease.executionId());
                break;
            }
        }
    }
}
