package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.skill.IqcSkillService;
import io.github.opensabre.iqc.skill.model.IqcSkill;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** HTTP management API for reusable IQC Skills. */
@RestController
@RequestMapping("/api/iqc/agent-assets/skills")
public class IqcSkillController {
    private final IqcSkillService service;
    public IqcSkillController(IqcSkillService service) { this.service = service; }

    @GetMapping
    @ResourcePermission(code = "iqc:skill:view", name = "查看 Skill", type = "iqc", description = "查询 Agent Skill 资产")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC Skill", module = "IQC_SKILL")
    public List<IqcSkill> list() { return service.list(); }

    @PostMapping
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "创建 Agent Skill 资产")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC Skill", module = "IQC_SKILL")
    @RateLimit(sceneCode = "iqc-skill-create", maxCount = 20, period = 60)
    public IqcSkill create(@RequestBody SkillRequest request) { return service.create(request.name(), request.code(), request.description(), request.instructions(), request.inputSchemaJson(), request.outputSchemaJson()); }

    @PutMapping("/{id}")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "修改 Agent Skill 资产")
    @Audit(operationType = OperationType.UPDATE, description = "修改 IQC Skill", module = "IQC_SKILL")
    public IqcSkill update(@PathVariable String id, @RequestBody SkillRequest request) { return service.update(id, request.name(), request.description(), request.instructions(), request.inputSchemaJson(), request.outputSchemaJson()); }

    @PostMapping("/{id}/enable")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "启用 Agent Skill 资产")
    @Audit(operationType = OperationType.UPDATE, description = "启用 IQC Skill", module = "IQC_SKILL")
    public IqcSkill enable(@PathVariable String id) { return service.setEnabled(id, true); }

    @PostMapping("/{id}/disable")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "停用 Agent Skill 资产")
    @Audit(operationType = OperationType.UPDATE, description = "停用 IQC Skill", module = "IQC_SKILL")
    public IqcSkill disable(@PathVariable String id) { return service.setEnabled(id, false); }

    public record SkillRequest(String name, String code, String description, String instructions,
                               String inputSchemaJson, String outputSchemaJson) { }
}
