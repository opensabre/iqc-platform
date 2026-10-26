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
    private final org.springframework.beans.factory.ObjectProvider<BulkTaskCoordinator> bulk;
    private final ThreadPoolTaskExecutor executor;
    private record Lease(String taskId,String executionId) { }
    private final Set<Lease> inFlight=ConcurrentHashMap.newKeySet();
    @Value("${iqc.task.worker-max-in-flight:8}") private int maximumInFlight=8;
    @Value("${iqc.task.execution-timeout-minutes:30}") private long timeoutMinutes=30;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private record MetricKey(String mode,String measure,String status) { }
    private record OperationalSnapshot(java.util.Map<MetricKey,Double> counts,long tick) { }
    private static final java.util.Map<String,Set<String>> METRIC_STATES=java.util.Map.of(
            "TASK_STATE",Set.of("QUEUED","RUNNING","PAUSE_REQUESTED","CANCEL_REQUESTED","PAUSED","BUDGET_WAIT","WAITING_INPUT","MATERIALIZING","SCHEDULED"),
            "COMPLETED_5M",Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED"),
            "UNMEASURED_TERMINAL",Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED"));
    private final java.util.concurrent.atomic.AtomicReference<OperationalSnapshot> operational=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong lastOperationalSuccess=new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    @Value("${iqc.task.metrics-delay-ms:30000}") private long sampleDelayMs=30000;
    @Value("${iqc.task.metrics-max-age-ms:90000}") private long maximumSampleAgeMs=90000;
    public DurableInspectionWorker(InspectionTaskMapper tasks,InspectionTaskService taskService,InspectionExecutionService execution,
            ThreadPoolTaskExecutor executor,org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters){
        this(tasks,taskService,execution,executor,meters,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DurableInspectionWorker(InspectionTaskMapper tasks,InspectionTaskService taskService,InspectionExecutionService execution,
            @Qualifier("iqcTaskExecutor") ThreadPoolTaskExecutor executor,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters,
            org.springframework.beans.factory.ObjectProvider<BulkTaskCoordinator> bulk){
        this.bulk=bulk;
        this.tasks=tasks;this.taskService=taskService;this.execution=execution;this.executor=executor;this.meters=meters==null?null:meters.getIfAvailable();
        if(this.meters!=null)try{
            for(String mode:Set.of("PRODUCTION","EVALUATION"))for(var measure:METRIC_STATES.entrySet())for(String status:measure.getValue()){
                var key=new MetricKey(mode,measure.getKey(),status);
                String name=switch(measure.getKey()){
                    case "TASK_STATE"->"iqc.task.persisted.state";
                    case "COMPLETED_5M"->"iqc.task.execution.completed.window";
                    default->"iqc.task.execution.unmeasured";
                };
                io.micrometer.core.instrument.Gauge.builder(name,this,worker->worker.operationalValue(key)).tags("mode",mode,"status",status).register(this.meters);
            }
            io.micrometer.core.instrument.Gauge.builder("iqc.task.metrics.sample.success",this,worker->worker.fresh(worker.operational.get())?1:0).register(this.meters);
            io.micrometer.core.instrument.Gauge.builder("iqc.task.metrics.sample.age",this,DurableInspectionWorker::operationalAgeSeconds).baseUnit("seconds").register(this.meters);
        }catch(RuntimeException unavailable){log.debug("Task operational gauge unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    public DurableInspectionWorker(InspectionTaskMapper tasks,InspectionTaskService taskService,InspectionExecutionService execution,
            @Qualifier("iqcTaskExecutor") ThreadPoolTaskExecutor executor){this(tasks,taskService,execution,executor,null);}
    private boolean fresh(OperationalSnapshot snapshot){
        return snapshot!=null&&System.nanoTime()-snapshot.tick()<=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maximumSampleAgeMs);
    }
    private double operationalValue(MetricKey key){var snapshot=operational.get();return fresh(snapshot)?snapshot.counts().getOrDefault(key,Double.NaN):Double.NaN;}
    private double operationalAgeSeconds(){long tick=lastOperationalSuccess.get();return tick==Long.MIN_VALUE?Double.NaN:Math.max(0,System.nanoTime()-tick)/1_000_000_000d;}
    /** Cached only: Prometheus scrapes never perform a database query. */
    @Scheduled(initialDelayString="${iqc.task.metrics-delay-ms:30000}",fixedDelayString="${iqc.task.metrics-delay-ms:30000}")
    public void sampleOperationalMetrics(){
        if(meters==null)return;
        try{
            var counts=new java.util.HashMap<MetricKey,Double>();
            for(String mode:Set.of("PRODUCTION","EVALUATION"))METRIC_STATES.forEach((measure,states)->states.forEach(status->counts.put(new MetricKey(mode,measure,status),0d)));
            var rows=tasks.operationalCounts();if(rows==null)throw new IllegalStateException("Missing operational counts");
            var seen=new java.util.HashSet<MetricKey>();
            for(var row:rows){
                if(row==null||row.countValue()<0)throw new IllegalStateException("Invalid operational count");
                var key=new MetricKey(row.mode(),row.measure(),row.status());
                if(!counts.containsKey(key)||!seen.add(key))throw new IllegalStateException("Invalid operational dimension");
                counts.put(key,(double)row.countValue());
            }
            long tick=System.nanoTime();lastOperationalSuccess.set(tick);operational.set(new OperationalSnapshot(java.util.Map.copyOf(counts),tick));
        }catch(RuntimeException unavailable){operational.set(null);log.debug("Task operational sample unavailable type={}",unavailable.getClass().getSimpleName());}
    }
    @jakarta.annotation.PostConstruct
    public void validate() {
        if(sampleDelayMs<1000||sampleDelayMs>3600000||maximumSampleAgeMs<sampleDelayMs*2||maximumSampleAgeMs>10800000)
            throw new IllegalArgumentException("Task metric sampling interval/maximum age invalid");
        if(maximumInFlight<1 || maximumInFlight>64 || timeoutMinutes<1)
            throw new IllegalArgumentException("worker-max-in-flight must be 1..64; timeout must be positive");
    }
    @Scheduled(fixedDelayString="${iqc.task.worker-delay-ms:2000}")
    public synchronized void discoverQueuedTasks() {
        int recovered=taskService.recoverStaleExecutions(Duration.ofMinutes(timeoutMinutes),false);
        if(recovered>0)log.warn("event=iqc_worker_stale_recovery recovered={}",recovered);
        if(inFlight.size()>=maximumInFlight || executor.getThreadPoolExecutor().getQueue().remainingCapacity()==0)return;
        var queued=tasks.selectList(Wrappers.<InspectionTask>lambdaQuery()
                .and(q->q.eq(InspectionTask::getStatus,"QUEUED").or(n->n.eq(InspectionTask::getTaskType,BulkTaskMaterializationService.TYPE)
                        .in(InspectionTask::getStatus,"PAUSE_REQUESTED","CANCEL_REQUESTED")))
                .isNotNull(InspectionTask::getCurrentExecutionId).orderByDesc(InspectionTask::getQueuePriority)
                .orderByAsc(InspectionTask::getCreatedTime).orderByAsc(InspectionTask::getId).last("LIMIT 100"));
        for(var task:queued) {
            if(inFlight.size()>=maximumInFlight)break;
            var lease=new Lease(task.getId(),task.getCurrentExecutionId());
            if(!inFlight.add(lease))continue;
            try {
                executor.execute(()->{
                    try{
                        if(BulkTaskMaterializationService.TYPE.equals(task.getTaskType())) {
                            if(bulk==null)throw new IllegalStateException("Bulk coordinator unavailable");
                            bulk.getObject().executePersisted(lease.taskId(),lease.executionId());
                        } else execution.executePersisted(lease.taskId(),lease.executionId());
                    }
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
