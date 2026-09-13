package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.label.LabelCollectionService;
import io.github.opensabre.iqc.label.model.LabelCollection;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** APIs for reusable label selections; collections do not define rule aggregation. */
@RestController @RequestMapping("/api/iqc/label-collections") @RequiredArgsConstructor
public class LabelCollectionController {
    private final LabelCollectionService service;
    @GetMapping @ResourcePermission(code="iqc:label-collection:view", name="查看标签集合", type="iqc", description="查询标签集合")
    public List<LabelCollection> list() { return service.list(); }
    @GetMapping("/{id}") @ResourcePermission(code="iqc:label-collection:view", name="查看标签集合详情", type="iqc", description="查询标签集合详情")
    public LabelCollectionService.Detail get(@PathVariable String id) { return service.get(id); }
    @PostMapping @ResourcePermission(code="iqc:label-collection:manage", name="管理标签集合", type="iqc", description="创建标签集合")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 标签集合", module="IQC_LABEL")
    public LabelCollectionService.Detail create(@RequestBody LabelCollectionService.CollectionRequest request) { return service.create(request); }
    @PutMapping("/{id}") @ResourcePermission(code="iqc:label-collection:manage", name="管理标签集合", type="iqc", description="修订标签集合")
    @Audit(operationType=OperationType.UPDATE, description="修订 IQC 标签集合", module="IQC_LABEL")
    public LabelCollectionService.Detail revise(@PathVariable String id, @RequestBody LabelCollectionService.CollectionRequest request) { return service.revise(id, request); }
    @PutMapping("/{id}/members") @ResourcePermission(code="iqc:label-collection:manage", name="管理标签集合", type="iqc", description="配置标签集合成员")
    @Audit(operationType=OperationType.UPDATE, description="配置 IQC 标签集合成员", module="IQC_LABEL")
    public LabelCollectionService.Detail members(@PathVariable String id, @RequestBody List<LabelCollectionService.MemberRequest> request) { return service.replaceMembers(id, request); }
    @PostMapping("/{id}/publish") @ResourcePermission(code="iqc:label-collection:manage", name="发布标签集合", type="iqc", description="发布标签集合")
    @Audit(operationType=OperationType.UPDATE, description="发布 IQC 标签集合", module="IQC_LABEL")
    public LabelCollection publish(@PathVariable String id) { return service.publish(id); }
    @PostMapping("/{id}/disable") @ResourcePermission(code="iqc:label-collection:manage", name="停用标签集合", type="iqc", description="停用标签集合")
    @Audit(operationType=OperationType.UPDATE, description="停用 IQC 标签集合", module="IQC_LABEL")
    public LabelCollection disable(@PathVariable String id) { return service.disable(id); }
}
