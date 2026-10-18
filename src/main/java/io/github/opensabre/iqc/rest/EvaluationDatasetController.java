package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.DatasetDraft;
import io.github.opensabre.iqc.quality.evaluation.EvaluationDatasetService;
import io.github.opensabre.iqc.quality.model.EvaluationDataset;
import io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion;
import io.github.opensabre.iqc.shared.IqcPage;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/iqc/evaluation/datasets")
public class EvaluationDatasetController {
    private final EvaluationDatasetService service;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationDatasetController(EvaluationDatasetService service,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection) {
        this.service=service; this.sensitive=sensitive; this.projection=projection;
    }
    public EvaluationDatasetController(EvaluationDatasetService service) { this(service,null,null); }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource) {
        if (sensitive==null || projection==null) throw IqcException.invalidState("数据集敏感读取授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null) {
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }


    @GetMapping
    @ResourcePermission(code="iqc:dataset:view", name="查看评测数据集", type="iqc", description="按数据范围分页查询评测数据集")
    @Audit(operationType=OperationType.QUERY, description="读取数据集目录", module="IQC_PRIVACY", request=false, response=false)
    public IqcPage<EvaluationDataset> page(@RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        var access=access("iqc:dataset:view");
        var page=service.page(current,size);
        return new IqcPage<>(page.records().stream().map(row -> projection.dataset(row,access,false)).toList(),page.current(),page.size(),page.total());
    }
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:dataset:detail:view", name="查看评测数据集草稿", type="iqc", description="查看数据集草稿和精确金标成员引用")
    @Audit(operationType=OperationType.QUERY, description="读取数据集草稿", module="IQC_PRIVACY", request=false, response=false, key="#id")
    public EvaluationDataset get(@PathVariable String id) {
        var access=access("iqc:dataset:detail:view");
        return projection.dataset(service.get(id),access,true);
    }

    @PostMapping
    @ResourcePermission(code="iqc:dataset:manage", name="创建评测数据集", type="iqc", description="使用已批准样本版本创建数据集")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 评测数据集", module="IQC_EVALUATION", request=false, response=false)
    public EvaluationDataset create(@RequestBody Edit request) {
        var access=access("iqc:dataset:manage");
        if (request == null) throw IqcException.invalidArgument("数据集请求不能为空");
        return projection.dataset(service.create(request.name(), request.draft()),access,false);
    }
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:dataset:update", name="更新评测数据集", type="iqc", description="按预期修订更新数据集成员")
    @Audit(operationType=OperationType.UPDATE, description="更新 IQC 评测数据集", module="IQC_EVALUATION", request=false, response=false, key="#id")
    public EvaluationDataset update(@PathVariable String id, @RequestBody Edit request) {
        var access=access("iqc:dataset:update");
        if (request == null || request.expectedRevision() == null) throw IqcException.invalidArgument("必须指定数据集预期修订");
        return projection.dataset(service.update(id, request.expectedRevision(), request.name(), request.draft()),access,false);
    }
    @PostMapping("/{id}/freeze")
    @ResourcePermission(code="iqc:dataset:freeze", name="冻结评测数据集版本", type="iqc", description="冻结精确金标成员、完整会话及摘要")
    @Audit(operationType=OperationType.CREATE, description="冻结 IQC 评测数据集版本", module="IQC_EVALUATION", request=false, response=false, key="#id")
    public EvaluationDatasetVersion freeze(@PathVariable String id, @RequestBody Revision request) {
        var access=access("iqc:dataset:freeze");
        if (request == null || request.expectedRevision() == null) throw IqcException.invalidArgument("必须指定数据集预期修订");
        return projection.datasetVersion(service.freeze(id, request.expectedRevision()),access,false);
    }
    @PostMapping("/{id}/availability")
    @ResourcePermission(code="iqc:dataset:availability", name="设置评测数据集可用性", type="iqc", description="设置数据集能否冻结或用于新的评测")
    @Audit(operationType=OperationType.UPDATE, description="设置 IQC 数据集可用性", module="IQC_EVALUATION", request=false, response=false, key="#id")
    public EvaluationDataset availability(@PathVariable String id, @RequestBody Availability request) {
        var access=access("iqc:dataset:availability");
        if (request == null || request.enabled() == null) throw IqcException.invalidArgument("必须指定数据集可用性");
        return projection.dataset(service.availability(id, request.enabled()),access,false);
    }
    @GetMapping("/{id}/versions")
    @ResourcePermission(code="iqc:dataset:versions:view", name="查看数据集版本历史", type="iqc", description="分页查看数据集冻结版本摘要")
    @Audit(operationType=OperationType.QUERY, description="读取数据集版本摘要", module="IQC_PRIVACY", request=false, response=false, key="#id")
    public IqcPage<EvaluationDatasetVersion> history(@PathVariable String id,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        var access=access("iqc:dataset:versions:view");
        var page=service.history(id,current,size);
        return new IqcPage<>(page.records().stream().map(row -> projection.datasetVersion(row,access,false)).toList(),page.current(),page.size(),page.total());
    }
    @GetMapping("/{id}/versions/{versionNo}")
    @ResourcePermission(code="iqc:dataset:version:view", name="查看数据集冻结快照", type="iqc", description="查看并校验数据集版本及全部金标快照")
    @Audit(operationType=OperationType.QUERY, description="读取数据集冻结版本", module="IQC_PRIVACY", request=false, response=false, key="#id + ':' + #versionNo")
    public EvaluationDatasetVersion version(@PathVariable String id, @PathVariable int versionNo) {
        var access=access("iqc:dataset:version:view");
        return projection.datasetVersion(service.version(id, versionNo),access,true);
    }
    public record Edit(String name, Integer expectedRevision, DatasetDraft draft) { }
    public record Revision(Integer expectedRevision) { }
    public record Availability(Boolean enabled) { }
}
