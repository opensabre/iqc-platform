package io.github.opensabre.iqc.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineRolloutPolicy;
import io.github.opensabre.iqc.pipelineasset.PipelineRolloutService;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iqc/pipelines/{id}/rollout")
@RequiredArgsConstructor
public class PipelineRolloutController {
    private final PipelineRolloutService rollouts;
    private final SensitiveReadAccessService access;

    public record Save(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            JsonNode expectedRevision,PipelineRolloutPolicy policy) { }
    @GetMapping
    @ResourcePermission(code="iqc:pipeline:rollout:view",name="查看流水线灰度策略",type="iqc",description="读取当前灰度配置修订，不返回冻结配置正文")
    @Audit(operationType=OperationType.QUERY,description="查看灰度策略",module="IQC_ROLLOUT",request=false,response=false,key="#id")
    public PipelineRolloutService.Current current(@PathVariable String id){
        authorize("iqc:pipeline:rollout:view");return rollouts.current(id);
    }
    @GetMapping("/revisions")
    @ResourcePermission(code="iqc:pipeline:rollout:history:view",name="查看流水线灰度修订历史",type="iqc",description="按修订游标读取不可变灰度历史")
    @Audit(operationType=OperationType.QUERY,description="查看灰度策略历史",module="IQC_ROLLOUT",request=false,response=false,key="#id")
    public List<PipelineRolloutService.Revision> history(@PathVariable String id,@RequestParam(required=false) Integer beforeRevision,
            @RequestParam(defaultValue="20") int limit){
        authorize("iqc:pipeline:rollout:history:view");return rollouts.history(id,beforeRevision,limit);
    }
    @PostMapping("/revisions")
    @ResourcePermission(code="iqc:pipeline:rollout:manage",name="修订流水线灰度策略",type="iqc",description="按预期修订追加策略，供后续灰度任务新请求使用")
    @Audit(operationType=OperationType.UPDATE,description="追加灰度策略修订",module="IQC_ROLLOUT",request=false,response=false,key="#id")
    @io.github.opensabre.governance.ratelimit.annotations.RateLimit(sceneCode="iqc-pipeline-rollout-save",maxCount=10,period=60)
    public PipelineRolloutService.Revision save(@PathVariable String id,@RequestBody Save request){
        authorize("iqc:pipeline:rollout:manage");
        var revision=request==null?null:request.expectedRevision();
        if(revision==null||!revision.isIntegralNumber()||!revision.canConvertToInt()||revision.intValue()<0)
            throw IqcException.invalidArgument("预期修订须为非负精确整数");
        return rollouts.append(id,revision.intValue(),request.policy());
    }
    private void authorize(String resource){
        access.current(Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
    }
}
