package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelineasset.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/iqc/pipelines")
public class PipelineAssetController {
    private final PipelineAssetService assets;
    private final InspectionTaskService tasks;
    public PipelineAssetController(PipelineAssetService assets,InspectionTaskService tasks) { this.assets=assets; this.tasks=tasks; }
    @GetMapping
    @ResourcePermission(code="iqc:pipeline:view",name="查看流水线目录",type="iqc",description="查询可见流水线")
    public IqcPage<PipelineAsset> page(@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size) { return assets.page(current,size); }
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:pipeline:detail:view",name="查看流水线",type="iqc",description="查询流水线草稿和发布指针")
    public PipelineAsset get(@PathVariable String id) { return assets.get(id); }
    @PostMapping
    @ResourcePermission(code="iqc:pipeline:manage",name="创建流水线",type="iqc",description="创建流水线草稿")
    @Audit(operationType=OperationType.CREATE,description="创建流水线",module="IQC_PIPELINE")
    public PipelineAsset create(@RequestBody Create body) { return assets.create(body.code(),body.name(),body.release()); }
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:pipeline:update",name="更新流水线",type="iqc",description="按修订更新流水线草稿")
    @Audit(operationType=OperationType.UPDATE,description="更新流水线",module="IQC_PIPELINE")
    public PipelineAsset update(@PathVariable String id,@RequestBody Update body) { return assets.update(id,body.expectedRevision(),body.name(),body.release()); }
    @PostMapping("/{id}/availability")
    @ResourcePermission(code="iqc:pipeline:availability",name="设置流水线可用性",type="iqc",description="启停流水线")
    @Audit(operationType=OperationType.UPDATE,description="设置流水线可用性",module="IQC_PIPELINE")
    public PipelineAsset availability(@PathVariable String id,@RequestBody Availability body) { return assets.availability(id,body.enabled()); }
    @GetMapping("/{id}/versions")
    @ResourcePermission(code="iqc:pipeline:versions:view",name="查看流水线历史",type="iqc",description="查询不可变历史版本")
    public List<PipelineAssetVersion> history(@PathVariable String id) { return assets.history(id); }
    @PostMapping("/{id}/trial")
    @ResourcePermission(code="iqc:pipeline:execute",name="试跑流水线",type="iqc",description="创建冻结草稿试跑任务")
    @Audit(operationType=OperationType.CREATE,description="创建流水线试跑任务",module="IQC_PIPELINE")
    public InspectionTask trial(@PathVariable String id,@RequestBody Trial body) {
        return tasks.createUsingPipelineSnapshot(assets.frozenDraft(id,body.expectedRevision()),body.name(),body.conversationIds(),1,body.requestId());
    }
    @PostMapping("/{id}/publish")
    @ResourcePermission(code="iqc:pipeline:publish",name="发布流水线",type="iqc",description="经真实试跑确认发布流水线版本")
    @Audit(operationType=OperationType.UPDATE,description="发布流水线",module="IQC_PIPELINE",response=true,key="#id")
    public PipelineAssetVersion publish(@PathVariable String id,@RequestBody Publish body) {
        if(body.qualityEvidence()==null && body.regressionEvidence()==null && body.forceReason()==null)
            return assets.publish(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed());
        return assets.publishMeasured(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed(),body.qualityEvidence(),body.regressionEvidence(),false,body.forceReason());
    }
    @GetMapping("/{id}/publication-gate")
    @ResourcePermission(code="iqc:pipeline:gate:view",name="查看流水线发布门禁",type="iqc",description="按冻结评测产物计算准入")
    public PipelinePublicationGateService.Decision gate(@PathVariable String id,@RequestParam int expectedRevision,
            @RequestParam String qualityTaskId,@RequestParam String qualityExecutionId,
            @RequestParam String regressionTaskId,@RequestParam String regressionExecutionId) {
        return assets.publicationPreview(id,expectedRevision,new PipelinePublicationGateService.ExecutionEvidence(qualityTaskId,qualityExecutionId),
                new PipelinePublicationGateService.ExecutionEvidence(regressionTaskId,regressionExecutionId));
    }
    @PostMapping("/{id}/force-publish")
    @ResourcePermission(code="iqc:pipeline:force:publish",name="管理员例外发布流水线",type="iqc",description="保留未通过门禁及管理员原因")
    @Audit(operationType=OperationType.UPDATE,description="管理员例外发布流水线",module="IQC_PIPELINE",response=true,key="#id")
    public PipelineAssetVersion forcePublish(@PathVariable String id,@RequestBody Publish body) {
        return assets.publishMeasured(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed(),body.qualityEvidence(),body.regressionEvidence(),true,body.forceReason());
    }
    @PostMapping("/{id}/tasks")
    @ResourcePermission(code="iqc:pipeline:task:create",name="创建流水线任务",type="iqc",description="使用不可变已发布流水线创建任务")
    @Audit(operationType=OperationType.CREATE,description="创建流水线任务",module="IQC_PIPELINE")
    public InspectionTask task(@PathVariable String id,@RequestBody Run body) {
        var release=assets.publishedRelease(id,body.versionNo());
        if(body.selector()!=null) {
            if(body.conversationIds()!=null && !body.conversationIds().isEmpty()) throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("Selector与显式会话IDs不能同时提交");
            return tasks.createUsingPipelineSelector(release,body.name(),body.selector(),body.concurrency(),body.requestId());
        }
        return tasks.createUsingPipelineSnapshot(release,body.name(),body.conversationIds(),body.concurrency(),body.requestId());
    }
    public record Create(String code,String name,PipelineRelease release) { }
    public record Update(int expectedRevision,String name,PipelineRelease release) { }
    public record Availability(boolean enabled) { }
    public record Trial(int expectedRevision,String name,List<String> conversationIds,String requestId) { }
    public record Publish(int expectedRevision,String trialTaskId,boolean resultsReviewed,
            PipelinePublicationGateService.ExecutionEvidence qualityEvidence,
            PipelinePublicationGateService.ExecutionEvidence regressionEvidence,String forceReason) {
        public Publish(int expectedRevision,String trialTaskId,boolean resultsReviewed) {
            this(expectedRevision,trialTaskId,resultsReviewed,null,null,null);
        }
    }
    public record Run(int versionNo,String name,List<String> conversationIds,Integer concurrency,String requestId,
            io.github.opensabre.iqc.selector.SelectorDefinition selector) {
        public Run(int versionNo,String name,List<String> conversationIds,Integer concurrency,String requestId) {
            this(versionNo,name,conversationIds,concurrency,requestId,null);
        }
    }
}
