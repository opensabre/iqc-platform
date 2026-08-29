package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.template.IqcTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes built-in quality templates and their controlled rule materialization operation. */
@RestController
@RequestMapping("/api/iqc/templates")
public class IqcTemplateController {
    private final IqcTemplateService templateService;

    public IqcTemplateController(IqcTemplateService templateService) { this.templateService = templateService; }

    @GetMapping
    @ResourcePermission(code = "iqc:template:view", name = "查看质检模板", type = "iqc", description = "查询内置质检模板")
    @RateLimit(sceneCode = "iqc-template-query", maxCount = 60, period = 60)
    public List<IqcTemplateService.QualityTemplate> list() { return templateService.list(); }

    @PostMapping("/{id}/rules")
    @ResourcePermission(code = "iqc:rule:manage", name = "从模板创建规则", type = "iqc", description = "从内置模板批量创建规则草稿")
    @Audit(operationType = OperationType.CREATE, description = "从 IQC 模板批量创建规则", module = "IQC_TEMPLATE")
    @RateLimit(sceneCode = "iqc-template-materialize", maxCount = 10, period = 60)
    public IqcTemplateService.MaterializationResult materialize(@PathVariable String id) {
        return templateService.materialize(id);
    }
}
