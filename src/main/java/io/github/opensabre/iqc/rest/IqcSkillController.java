package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.skill.IqcSkillService;
import io.github.opensabre.iqc.skill.model.IqcSkill;
import io.github.opensabre.iqc.skill.model.IqcSkillVersion;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** HTTP management API for reusable IQC Skills. */
@RestController
@RequestMapping("/api/iqc/agent-assets/skills")
public class IqcSkillController {
    private final IqcSkillService service;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public IqcSkillController(IqcSkillService service,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){this.service=service;this.sensitive=sensitive;this.projection=projection;}
    public IqcSkillController(IqcSkillService service){this(service,null,null);}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource){
        if(sensitive==null||projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("Skill响应授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:skill:view", name = "查看 Skill", type = "iqc", description = "查询 Agent Skill 资产")
    @Audit(operationType=OperationType.QUERY,description="读取Skill配置",module="IQC_PRIVACY",request=false,response=false)
    public List<IqcSkill> list() { var access=access("iqc:skill:view");return service.list().stream().map(row->projection.skill(row,access,true)).toList(); }

    @PostMapping
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "创建 Agent Skill 资产")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC Skill", module = "IQC_SKILL", request=false, response=false)
    @RateLimit(sceneCode = "iqc-skill-create", maxCount = 20, period = 60)
    public IqcSkill create(@RequestBody SkillRequest request) { var access=access("iqc:skill:manage");return projection.skill(service.create(request.name(), request.code(), request.description(), request.instructions(), request.inputSchemaJson(), request.outputSchemaJson()),access,false); }

    @PutMapping("/{id}")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "修改 Agent Skill 资产")
    @Audit(operationType = OperationType.UPDATE, description = "修改 IQC Skill", module = "IQC_SKILL", request=false, response=false, key="#id")
    public IqcSkill update(@PathVariable String id, @RequestBody SkillRequest request) { var access=access("iqc:skill:manage");return projection.skill(service.update(id, request.name(), request.description(), request.instructions(), request.inputSchemaJson(), request.outputSchemaJson()),access,false); }

    @PostMapping("/{id}/enable")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "启用 Agent Skill 资产")
    @Audit(operationType = OperationType.UPDATE, description = "启用 IQC Skill", module = "IQC_SKILL", request=false,response=false,key="#id")
    public IqcSkill enable(@PathVariable String id) { var access=access("iqc:skill:manage");return projection.skill(service.setEnabled(id,true),access,false); }

    @PostMapping("/{id}/disable")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "停用 Agent Skill 资产")
    @Audit(operationType = OperationType.UPDATE, description = "停用 IQC Skill", module = "IQC_SKILL", request=false,response=false,key="#id")
    public IqcSkill disable(@PathVariable String id) { var access=access("iqc:skill:manage");return projection.skill(service.setEnabled(id,false),access,false); }

    @GetMapping("/{id}/versions")
    @ResourcePermission(code = "iqc:skill:view", name = "查看 Skill 版本", type = "iqc", description = "查询 Skill 历史版本")
    @Audit(operationType=OperationType.QUERY,description="读取Skill版本",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public List<IqcSkillVersion> versions(@PathVariable String id) { var access=access("iqc:skill:view");return service.versions(id).stream().map(row->projection.skillVersion(row,access)).toList(); }

    @PostMapping("/{id}/versions/{versionNo}/rollback")
    @ResourcePermission(code = "iqc:skill:manage", name = "管理 Skill", type = "iqc", description = "回滚 Skill 历史版本")
    @Audit(operationType = OperationType.UPDATE, description = "回滚 IQC Skill", module = "IQC_SKILL", request=false,response=false,key="#id")
    public IqcSkill rollback(@PathVariable String id, @PathVariable int versionNo) { var access=access("iqc:skill:manage");return projection.skill(service.rollback(id,versionNo),access,false); }

    public record SkillRequest(String name, String code, String description, String instructions,
                               String inputSchemaJson, String outputSchemaJson) { }
}
