package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.schedule.TaskTriggerService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/triggers")
public class TaskTriggerController {
    private final TaskTriggerService triggers;
    public record Receipt(String taskId,String status,String source,String eventId,String payloadHash,String requestHash,
            String currentExecutionId,String waitingReason,java.time.LocalDateTime retryNotBeforeUtc) {
        static Receipt from(InspectionTask task){return new Receipt(task.getId(),task.getStatus(),task.getTriggerSource(),task.getTriggerEventId(),
                task.getTriggerPayloadHash(),task.getTriggerRequestHash(),task.getCurrentExecutionId(),task.getTriggerError(),task.getTriggerRetryNotBeforeUtc());}
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResourcePermission(code="iqc:trigger:receive",name="接收事件/API触发",type="iqc",description="持久接收并幂等恢复质检触发")
    @Audit(operationType=OperationType.CREATE,description="接收IQC事件/API触发",module="IQC_TRIGGER",request=false,response=true)
    @RateLimit(sceneCode="iqc-trigger-receive",maxCount=120,period=60)
    public Receipt receive(@RequestBody String requestJson){return Receipt.from(triggers.receive(requestJson));}
}
