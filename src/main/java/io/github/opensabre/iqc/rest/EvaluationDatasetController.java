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
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/evaluation/datasets")
public class EvaluationDatasetController {
    private final EvaluationDatasetService service;

    @GetMapping
    @ResourcePermission(code="iqc:dataset:view", name="查看评测数据集", type="iqc", description="按数据范围分页查询评测数据集")
    public IqcPage<EvaluationDataset> page(@RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        return service.page(current, size);
    }
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:dataset:detail:view", name="查看评测数据集草稿", type="iqc", description="查看数据集草稿和精确金标成员引用")
    public EvaluationDataset get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    @ResourcePermission(code="iqc:dataset:manage", name="创建评测数据集", type="iqc", description="使用已批准样本版本创建数据集")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 评测数据集", module="IQC_EVALUATION")
    public EvaluationDataset create(@RequestBody Edit request) {
        if (request == null) throw IqcException.invalidArgument("数据集请求不能为空");
        return service.create(request.name(), request.draft());
    }
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:dataset:update", name="更新评测数据集", type="iqc", description="按预期修订更新数据集成员")
    @Audit(operationType=OperationType.UPDATE, description="更新 IQC 评测数据集", module="IQC_EVALUATION")
    public EvaluationDataset update(@PathVariable String id, @RequestBody Edit request) {
        if (request == null || request.expectedRevision() == null) throw IqcException.invalidArgument("必须指定数据集预期修订");
        return service.update(id, request.expectedRevision(), request.name(), request.draft());
    }
    @PostMapping("/{id}/freeze")
    @ResourcePermission(code="iqc:dataset:freeze", name="冻结评测数据集版本", type="iqc", description="冻结精确金标成员、完整会话及摘要")
    @Audit(operationType=OperationType.CREATE, description="冻结 IQC 评测数据集版本", module="IQC_EVALUATION")
    public EvaluationDatasetVersion freeze(@PathVariable String id, @RequestBody Revision request) {
        if (request == null || request.expectedRevision() == null) throw IqcException.invalidArgument("必须指定数据集预期修订");
        return service.freeze(id, request.expectedRevision());
    }
    @PostMapping("/{id}/availability")
    @ResourcePermission(code="iqc:dataset:availability", name="设置评测数据集可用性", type="iqc", description="设置数据集能否冻结或用于新的评测")
    @Audit(operationType=OperationType.UPDATE, description="设置 IQC 数据集可用性", module="IQC_EVALUATION")
    public EvaluationDataset availability(@PathVariable String id, @RequestBody Availability request) {
        if (request == null || request.enabled() == null) throw IqcException.invalidArgument("必须指定数据集可用性");
        return service.availability(id, request.enabled());
    }
    @GetMapping("/{id}/versions")
    @ResourcePermission(code="iqc:dataset:versions:view", name="查看数据集版本历史", type="iqc", description="分页查看数据集冻结版本摘要")
    public IqcPage<EvaluationDatasetVersion> history(@PathVariable String id,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        return service.history(id, current, size);
    }
    @GetMapping("/{id}/versions/{versionNo}")
    @ResourcePermission(code="iqc:dataset:version:view", name="查看数据集冻结快照", type="iqc", description="查看并校验数据集版本及全部金标快照")
    public EvaluationDatasetVersion version(@PathVariable String id, @PathVariable int versionNo) { return service.version(id, versionNo); }
    public record Edit(String name, Integer expectedRevision, DatasetDraft draft) { }
    public record Revision(Integer expectedRevision) { }
    public record Availability(Boolean enabled) { }
}
