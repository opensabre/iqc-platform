package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.label.LabelTaxonomyService;
import io.github.opensabre.iqc.label.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Business taxonomy APIs; executable expressions remain owned by the existing rule APIs. */
@RestController @RequestMapping("/api/iqc/labels") @RequiredArgsConstructor
public class LabelController {
    private final LabelTaxonomyService service;

    @GetMapping("/tree")
    @ResourcePermission(code="iqc:label:view", name="查看标签树", type="iqc", description="查询洞察标签树")
    public LabelTaxonomyService.TaxonomyTree tree(@RequestParam(required=false) String keyword) { return service.tree(keyword); }

    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:label:view", name="查看标签详情", type="iqc", description="查询标签值与规则绑定")
    public LabelTaxonomyService.LabelDetail detail(@PathVariable String id) { return service.detail(id); }

    @PostMapping("/categories")
    @ResourcePermission(code="iqc:label:manage", name="管理一级标签", type="iqc", description="创建一级标签")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 一级标签", module="IQC_LABEL")
    public LabelCategory createCategory(@RequestBody LabelTaxonomyService.CategoryRequest request) { return service.createCategory(request); }

    @PostMapping("/groups")
    @ResourcePermission(code="iqc:label:manage", name="管理标签群组", type="iqc", description="创建标签群组")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 标签群组", module="IQC_LABEL")
    public LabelGroup createGroup(@RequestBody LabelTaxonomyService.GroupRequest request) { return service.createGroup(request); }

    @PutMapping("/categories/{id}") @ResourcePermission(code="iqc:label:manage", name="管理一级标签", type="iqc", description="修订一级标签")
    @Audit(operationType=OperationType.UPDATE, description="修订 IQC 一级标签", module="IQC_LABEL")
    public LabelCategory reviseCategory(@PathVariable String id, @RequestBody LabelTaxonomyService.CategoryRequest request) { return service.reviseCategory(id, request); }

    @PutMapping("/groups/{id}") @ResourcePermission(code="iqc:label:manage", name="管理标签群组", type="iqc", description="修订标签群组")
    @Audit(operationType=OperationType.UPDATE, description="修订 IQC 标签群组", module="IQC_LABEL")
    public LabelGroup reviseGroup(@PathVariable String id, @RequestBody LabelTaxonomyService.GroupRequest request) { return service.reviseGroup(id, request); }

    @PostMapping
    @ResourcePermission(code="iqc:label:manage", name="管理标签", type="iqc", description="创建洞察标签")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 洞察标签", module="IQC_LABEL")
    public InsightLabel createLabel(@RequestBody LabelTaxonomyService.LabelRequest request) { return service.createLabel(request); }

    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:label:manage", name="编辑标签", type="iqc", description="创建标签的新草稿版本")
    @Audit(operationType=OperationType.UPDATE, description="修订 IQC 洞察标签", module="IQC_LABEL")
    public InsightLabel revise(@PathVariable String id, @RequestBody LabelTaxonomyService.LabelRequest request) { return service.revise(id, request); }

    @PutMapping("/{id}/values")
    @ResourcePermission(code="iqc:label:manage", name="管理标签值", type="iqc", description="配置标签结构化值")
    @Audit(operationType=OperationType.UPDATE, description="配置 IQC 标签值", module="IQC_LABEL")
    public List<LabelValueDefinition> values(@PathVariable String id, @RequestBody List<LabelTaxonomyService.ValueRequest> request) { return service.replaceValues(id, request); }

    @PutMapping("/{id}/bindings")
    @ResourcePermission(code="iqc:label:manage", name="绑定标签规则", type="iqc", description="配置标签判定规则")
    @Audit(operationType=OperationType.UPDATE, description="绑定 IQC 标签规则", module="IQC_LABEL")
    public List<LabelRuleBinding> bindings(@PathVariable String id, @RequestBody List<LabelTaxonomyService.BindingRequest> request) { return service.replaceBindings(id, request); }

    @PostMapping("/{id}/publish")
    @ResourcePermission(code="iqc:label:approve", name="发布标签", type="iqc", description="发布已配置的洞察标签")
    @Audit(operationType=OperationType.UPDATE, description="发布 IQC 洞察标签", module="IQC_LABEL")
    public InsightLabel publish(@PathVariable String id) { return service.publish(id); }

    @PostMapping("/{id}/disable") @ResourcePermission(code="iqc:label:manage", name="停用标签", type="iqc", description="停用洞察标签")
    @Audit(operationType=OperationType.UPDATE, description="停用 IQC 洞察标签", module="IQC_LABEL")
    public InsightLabel disable(@PathVariable String id) { return service.disableLabel(id); }

    @PostMapping("/groups/{id}/disable") @ResourcePermission(code="iqc:label:manage", name="停用标签群组", type="iqc", description="停用空标签群组")
    @Audit(operationType=OperationType.UPDATE, description="停用 IQC 标签群组", module="IQC_LABEL")
    public LabelGroup disableGroup(@PathVariable String id) { return service.disableGroup(id); }

    @PostMapping("/categories/{id}/disable") @ResourcePermission(code="iqc:label:manage", name="停用一级标签", type="iqc", description="停用空一级标签")
    @Audit(operationType=OperationType.UPDATE, description="停用 IQC 一级标签", module="IQC_LABEL")
    public LabelCategory disableCategory(@PathVariable String id) { return service.disableCategory(id); }
}
