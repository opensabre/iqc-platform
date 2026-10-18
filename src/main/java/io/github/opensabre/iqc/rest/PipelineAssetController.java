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
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public PipelineAssetController(PipelineAssetService assets,InspectionTaskService tasks,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection) {
        this.assets=assets;this.tasks=tasks;this.sensitive=sensitive;this.projection=projection;
    }
    public PipelineAssetController(PipelineAssetService assets,InspectionTaskService tasks){this(assets,tasks,null,null);}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("流水线任务响应授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.opensabre.iqc.selector.SelectorAssetService selectorAssets;
    @GetMapping
    @ResourcePermission(code="iqc:pipeline:view",name="查看流水线目录",type="iqc",description="查询可见流水线")
    public IqcPage<PipelineAsset> page(@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size) { return assets.page(current,size); }
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:pipeline:detail:view",name="查看流水线",type="iqc",description="查询流水线草稿和发布指针")
    public PipelineAsset get(@PathVariable String id) { return assets.get(id); }
    @PostMapping
    @ResourcePermission(code="iqc:pipeline:manage",name="创建流水线",type="iqc",description="创建流水线草稿")
    @Audit(operationType=OperationType.CREATE,description="创建流水线",module="IQC_PIPELINE", request=false, response=false)
    public PipelineAsset create(@RequestBody Create body) { return assets.create(body.code(),body.name(),body.release()); }
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:pipeline:update",name="更新流水线",type="iqc",description="按修订更新流水线草稿")
    @Audit(operationType=OperationType.UPDATE,description="更新流水线",module="IQC_PIPELINE", request=false, response=false, key="#id")
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
    @Audit(operationType=OperationType.CREATE,description="创建流水线试跑任务",module="IQC_PIPELINE",request=false,response=false,key="#id")
    public InspectionTask trial(@PathVariable String id,@RequestBody Trial body) {
        var access=access("iqc:pipeline:execute");
        return projection.taskReceipt(tasks.createUsingPipelineSnapshot(assets.frozenDraft(id,body.expectedRevision()),body.name(),body.conversationIds(),1,body.requestId()),access);
    }
    @PostMapping("/{id}/publish")
    @ResourcePermission(code="iqc:pipeline:publish",name="发布流水线",type="iqc",description="经真实试跑确认发布流水线版本")
    @Audit(operationType=OperationType.UPDATE,description="发布流水线",module="IQC_PIPELINE",response=false,key="#id", request=false)
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
    @Audit(operationType=OperationType.UPDATE,description="管理员例外发布流水线",module="IQC_PIPELINE",response=false,key="#id", request=false)
    public PipelineAssetVersion forcePublish(@PathVariable String id,@RequestBody Publish body) {
        return assets.publishMeasured(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed(),body.qualityEvidence(),body.regressionEvidence(),true,body.forceReason());
    }
    @PostMapping("/{id}/tasks")
    @ResourcePermission(code="iqc:pipeline:task:create",name="创建流水线任务",type="iqc",description="使用不可变已发布流水线创建任务")
    @Audit(operationType=OperationType.CREATE,description="创建流水线任务",module="IQC_PIPELINE",request=false,response=false,key="#id")
    public InspectionTask task(@PathVariable String id,@RequestBody Run body) {
        var access=access("iqc:pipeline:task:create");
        var release=assets.publishedRelease(id,body.versionNo());
        if(body.selectorAsset()!=null) {
            if(body.selector()!=null || (body.conversationIds()!=null && !body.conversationIds().isEmpty()))
                throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("Selector资产、内联Selector与会话IDs不能同时提交");
            var selector=selectorAssets.published(body.selectorAsset().selectorId(),body.selectorAsset().versionNo());
            return projection.taskReceipt(tasks.createUsingPipelineSelectorAsset(release,body.name(),selector,body.concurrency(),body.requestId()),access);
        }
        if(body.selector()!=null) {
            if(body.conversationIds()!=null && !body.conversationIds().isEmpty()) throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("Selector与显式会话IDs不能同时提交");
            return projection.taskReceipt(tasks.createUsingPipelineSelector(release,body.name(),body.selector(),body.concurrency(),body.requestId()),access);
        }
        return projection.taskReceipt(tasks.createUsingPipelineSnapshot(release,body.name(),body.conversationIds(),body.concurrency(),body.requestId()),access);
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
            io.github.opensabre.iqc.selector.SelectorDefinition selector,
            io.github.opensabre.iqc.selector.SelectorAssetService.Reference selectorAsset) {
        public Run(int versionNo,String name,List<String> conversationIds,Integer concurrency,String requestId,
                io.github.opensabre.iqc.selector.SelectorDefinition selector) {
            this(versionNo,name,conversationIds,concurrency,requestId,selector,null);
        }
        public Run(int versionNo,String name,List<String> conversationIds,Integer concurrency,String requestId) {
            this(versionNo,name,conversationIds,concurrency,requestId,null,null);
        }
    }
}
