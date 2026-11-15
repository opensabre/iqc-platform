package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.*;
import io.github.opensabre.iqc.label.LabelActionService;
import io.github.opensabre.iqc.label.model.LabelActionDefinition;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Reusable action metadata and label links; no execution or scoring endpoint is provided. */
@RestController @RequestMapping("/api/iqc") @RequiredArgsConstructor
public class LabelActionController {
    private final LabelActionService service;
    @GetMapping("/label-actions")
    @ResourcePermission(code="iqc:label:view", name="查看标签动作", type="iqc", description="查询可复用动作")
    public List<LabelActionDefinition> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="100") int size) { return service.list(page, size); }
    @PostMapping("/label-actions")
    @ResourcePermission(code="iqc:label:manage", name="管理标签动作", type="iqc", description="创建动作定义")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 动作定义", module="IQC_LABEL")
    public LabelActionDefinition create(@RequestBody LabelActionService.Request request) { return service.save(null, request); }
    @PutMapping("/label-actions/{id}")
    @ResourcePermission(code="iqc:label:manage", name="管理标签动作", type="iqc", description="修订动作定义")
    @Audit(operationType=OperationType.UPDATE, description="修订 IQC 动作定义", module="IQC_LABEL")
    public LabelActionDefinition update(@PathVariable Long id, @RequestBody LabelActionService.Request request) { return service.save(id, request); }
    @PostMapping("/label-actions/{id}/disable")
    @ResourcePermission(code="iqc:label:manage", name="管理标签动作", type="iqc", description="停用未引用动作")
    @Audit(operationType=OperationType.UPDATE, description="停用 IQC 动作定义", module="IQC_LABEL")
    public void disable(@PathVariable Long id) { service.disableOrDelete(id, false); }
    @DeleteMapping("/label-actions/{id}")
    @ResourcePermission(code="iqc:label:manage", name="管理标签动作", type="iqc", description="删除未引用动作")
    @Audit(operationType=OperationType.DELETE, description="删除 IQC 动作定义", module="IQC_LABEL")
    public void delete(@PathVariable Long id) { service.disableOrDelete(id, true); }
    @GetMapping("/labels/{id}/actions")
    @ResourcePermission(code="iqc:label:view", name="查看标签动作", type="iqc", description="查询标签动作绑定")
    public List<LabelActionService.Snapshot> bindings(@PathVariable String id) { return service.snapshots(id); }
    @PutMapping("/labels/{id}/actions")
    @ResourcePermission(code="iqc:label:manage", name="绑定标签动作", type="iqc", description="替换标签动作绑定")
    @Audit(operationType=OperationType.UPDATE, description="绑定 IQC 标签动作", module="IQC_LABEL")
    public List<LabelActionService.Snapshot> bind(@PathVariable String id, @RequestBody List<Long> actionIds) { return service.replace(id, actionIds); }
}
