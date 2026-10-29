package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.finding.FindingCatalogService;
import io.github.opensabre.iqc.finding.FindingDefinition;
import io.github.opensabre.iqc.finding.model.FindingCatalog;
import io.github.opensabre.iqc.finding.model.FindingCatalogVersion;
import io.github.opensabre.iqc.shared.IqcPage;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/iqc/findings/catalog")
public class FindingCatalogController {
    private final FindingCatalogService service;
    public FindingCatalogController(FindingCatalogService service) { this.service = service; }
    @GetMapping
    @ResourcePermission(code="iqc:finding:view",name="查看事实目录",type="iqc",description="查看跨引擎事实定义")
    public IqcPage<FindingCatalog> page(@RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) { return service.page(current,size); }
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:finding:detail:view",name="查看事实定义",type="iqc",description="查看事实草稿与发布指针")
    public FindingCatalog get(@PathVariable String id) { return service.get(id); }
    @PostMapping
    @ResourcePermission(code="iqc:finding:manage",name="创建事实定义",type="iqc",description="创建事实目录草稿")
    @Audit(operationType=OperationType.CREATE,description="创建事实定义",module="IQC_FINDING")
    public FindingCatalog create(@RequestBody FindingDefinition definition) { return service.create(definition); }
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:finding:update",name="修改事实定义",type="iqc",description="按修订更新事实草稿")
    @Audit(operationType=OperationType.UPDATE,description="修改事实定义",module="IQC_FINDING")
    public FindingCatalog update(@PathVariable String id,@RequestBody Update request) { return service.update(id,request.expectedRevision(),request.definition()); }
    @PostMapping("/{id}/publish")
    @ResourcePermission(code="iqc:finding:publish",name="发布事实定义",type="iqc",description="发布不可变事实定义版本")
    @Audit(operationType=OperationType.UPDATE,description="发布事实定义",module="IQC_FINDING")
    public FindingCatalogVersion publish(@PathVariable String id,@RequestBody Publish request) { return service.publish(id,request.expectedRevision()); }
    @PostMapping("/{id}/availability")
    @ResourcePermission(code="iqc:finding:availability",name="设置事实可用性",type="iqc",description="启用或停用事实定义")
    @Audit(operationType=OperationType.UPDATE,description="设置事实定义可用性",module="IQC_FINDING")
    public FindingCatalog availability(@PathVariable String id,@RequestBody Availability request) { return service.setEnabled(id,request.enabled()); }
    @GetMapping("/{id}/versions")
    @ResourcePermission(code="iqc:finding:versions:view",name="查看事实版本",type="iqc",description="查看不可变事实定义历史")
    public List<FindingCatalogVersion> history(@PathVariable String id) { return service.history(id); }
    public record Update(int expectedRevision,FindingDefinition definition) { }
    public record Publish(int expectedRevision) { }
    public record Availability(boolean enabled) { }
}
