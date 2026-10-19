package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskService;
import io.github.opensabre.iqc.privacy.ResultReadProjection;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iqc/pipelines/{id}/rollout/tasks")
@RequiredArgsConstructor
public class PipelineRolloutTaskController {
    private final PipelineRolloutTaskService tasks;
    private final SensitiveReadAccessService sensitive;
    private final ResultReadProjection projection;

    public record Create(String name,List<String> conversationIds,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            JsonNode concurrency,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            JsonNode requestKey) { }
    @PostMapping
    @ResourcePermission(code="iqc:pipeline:rollout:task:create",name="按灰度策略创建流水线任务",type="iqc",description="新请求按当前策略分配，重放沿用原冻结任务")
    @Audit(operationType=OperationType.CREATE,description="创建灰度流水线任务",module="IQC_ROLLOUT",request=false,response=false,key="#id")
    @io.github.opensabre.governance.ratelimit.annotations.RateLimit(sceneCode="iqc-pipeline-rollout-task-create",maxCount=20,period=60)
    public InspectionTask create(@PathVariable String id,@RequestBody Create request){
        var access=sensitive.current(Set.of("iqc:pipeline:rollout:task:create","iqc:pipeline:task:create"));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        if(request==null||request.requestKey()==null||!request.requestKey().isTextual())
            throw IqcException.invalidArgument("灰度任务请求键须为字符串");
        Integer concurrency=null;
        if(request.concurrency()!=null&&!request.concurrency().isNull()){
            if(!request.concurrency().isIntegralNumber()||!request.concurrency().canConvertToInt())
                throw IqcException.invalidArgument("并发数须为精确整数");
            concurrency=request.concurrency().intValue();
        }
        return projection.taskReceipt(tasks.create(id,request.name(),request.conversationIds(),concurrency,request.requestKey().textValue()),access);
    }
}
