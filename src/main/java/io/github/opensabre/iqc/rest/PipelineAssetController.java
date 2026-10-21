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
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("流水线响应授权组件不可用");
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
    @GetMapping("/fusion-strategies")
    @ResourcePermission(code="iqc:pipeline:fusion:strategy:view",name="查看融合扩展目录",type="iqc",description="读取部署安装的精确融合扩展版本与实现摘要")
    @Audit(operationType=OperationType.QUERY,description="读取融合扩展目录",module="IQC_PIPELINE",request=false,response=false)
    public List<io.github.opensabre.iqc.fusion.FusionExtensionRegistry.Descriptor> fusionStrategies(){
        access("iqc:pipeline:fusion:strategy:view");return io.github.opensabre.iqc.fusion.FusionExtensionRegistry.system().descriptors();
    }
    @GetMapping
    @ResourcePermission(code="iqc:pipeline:view",name="查看流水线目录",type="iqc",description="查询可见流水线")
    @Audit(operationType=OperationType.QUERY,description="读取流水线目录",module="IQC_PRIVACY",request=false,response=false)
    public IqcPage<PipelineAsset> page(@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size) { var access=access("iqc:pipeline:view");var page=assets.page(current,size);return new IqcPage<>(page.records().stream().map(row->projection.pipelineAsset(row,access,false)).toList(),page.current(),page.size(),page.total()); }
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:pipeline:detail:view",name="查看流水线",type="iqc",description="查询流水线草稿和发布指针")
    @Audit(operationType=OperationType.QUERY,description="读取流水线草稿",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public PipelineAsset get(@PathVariable String id) { var access=access("iqc:pipeline:detail:view");return projection.pipelineAsset(assets.get(id),access,true); }
    @PostMapping
    @ResourcePermission(code="iqc:pipeline:manage",name="创建流水线",type="iqc",description="创建流水线草稿")
    @Audit(operationType=OperationType.CREATE,description="创建流水线",module="IQC_PIPELINE", request=false, response=false)
    public PipelineAsset create(@RequestBody Create body) { var access=access("iqc:pipeline:manage");return projection.pipelineAsset(assets.create(body.code(),body.name(),body.release()),access,false); }
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:pipeline:update",name="更新流水线",type="iqc",description="按修订更新流水线草稿")
    @Audit(operationType=OperationType.UPDATE,description="更新流水线",module="IQC_PIPELINE", request=false, response=false, key="#id")
    public PipelineAsset update(@PathVariable String id,@RequestBody Update body) { var access=access("iqc:pipeline:update");return projection.pipelineAsset(assets.update(id,body.expectedRevision(),body.name(),body.release()),access,false); }
    @PostMapping("/{id}/availability")
    @ResourcePermission(code="iqc:pipeline:availability",name="设置流水线可用性",type="iqc",description="启停流水线")
    @Audit(operationType=OperationType.UPDATE,description="设置流水线可用性",module="IQC_PIPELINE",request=false,response=false,key="#id")
    public PipelineAsset availability(@PathVariable String id,@RequestBody Availability body) { var access=access("iqc:pipeline:availability");return projection.pipelineAsset(assets.availability(id,body.enabled()),access,false); }
    @GetMapping("/{id}/versions")
    @ResourcePermission(code="iqc:pipeline:versions:view",name="查看流水线历史",type="iqc",description="查询不可变历史版本")
    @Audit(operationType=OperationType.QUERY,description="读取流水线历史",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public List<PipelineAssetVersion> history(@PathVariable String id) { var access=access("iqc:pipeline:versions:view");return assets.history(id).stream().map(row->projection.pipelineVersion(row,access,true)).toList(); }
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
        var access=access("iqc:pipeline:publish");
        var published=body.qualityEvidence()==null && body.regressionEvidence()==null && body.forceReason()==null
                ?assets.publish(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed())
                :assets.publishMeasured(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed(),body.qualityEvidence(),body.regressionEvidence(),false,body.forceReason());
        return projection.pipelineVersion(published,access,false);
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
        var access=access("iqc:pipeline:force:publish");return projection.pipelineVersion(assets.publishMeasured(id,body.expectedRevision(),body.trialTaskId(),body.resultsReviewed(),body.qualityEvidence(),body.regressionEvidence(),true,body.forceReason()),access,false);
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
    public record RolloutPreview(PipelineRolloutPolicy policy,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            com.fasterxml.jackson.databind.JsonNode requestKey) { }
    @PostMapping("/{id}/rollout-preview")
    @ResourcePermission(code="iqc:pipeline:rollout:preview",name="预览流水线灰度分配",type="iqc",description="已发布版本单变量校验与确定性分桶，不改变实际路由")
    @Audit(operationType=OperationType.QUERY,description="预览流水线灰度分配",module="IQC_ROLLOUT",request=false,response=false,key="#id")
    @io.github.opensabre.governance.ratelimit.annotations.RateLimit(sceneCode="iqc-pipeline-rollout-preview",maxCount=30,period=60)
    public PipelineRolloutPolicy.Decision rolloutPreview(@PathVariable String id,@RequestBody RolloutPreview request){
        access("iqc:pipeline:rollout:preview");
        if(request==null||request.requestKey()==null||!request.requestKey().isTextual()
                ||!request.requestKey().textValue().matches("[A-Za-z0-9:_-]{1,128}"))
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("灰度预览需指定有效字符串请求键");
        return assets.previewRollout(id,request.policy(),request.requestKey().textValue());
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
