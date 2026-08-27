package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.agent.QualityAgentService;
import io.github.opensabre.iqc.agent.AgentEffectService;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.rule.QualityRuleService;
import io.github.opensabre.iqc.rule.QualityRuleSetService;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import io.github.opensabre.iqc.rule.model.QualityRuleSet;
import io.github.opensabre.iqc.rule.model.QualityRuleSetVersion;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/iqc/config")
public class QualityConfigController {
    private final QualityAgentService agentService;
    private final AgentEffectService agentEffectService;
    private final QualityRuleService ruleService;
    private final QualityRuleSetService ruleSetService;

    public QualityConfigController(QualityAgentService agentService, AgentEffectService agentEffectService,
                                   QualityRuleService ruleService, QualityRuleSetService ruleSetService) {
        this.agentService = agentService; this.agentEffectService = agentEffectService; this.ruleService = ruleService; this.ruleSetService = ruleSetService;
    }

    @GetMapping("/agents")
    @ResourcePermission(code = "iqc:agent:view", name = "查看 Agent", type = "iqc", description = "查询 Agent")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC Agent", module = "IQC_AGENT")
    public List<QualityAgent> agents() { return agentService.list(); }

    @PostMapping("/agents")
    @ResourcePermission(code = "iqc:agent:manage", name = "管理 Agent", type = "iqc", description = "创建 Agent")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC Agent", module = "IQC_AGENT")
    @RateLimit(sceneCode = "iqc-agent-create", maxCount = 20, period = 60)
    public QualityAgent createAgent(@RequestBody AgentRequest request) { return agentService.create(request.name(), request.code(), request.description(), request.configJson()); }

    @PostMapping("/agents/{id}/submit")
    @ResourcePermission(code = "iqc:agent:manage", name = "提交 Agent 审批", type = "iqc", description = "提交 Agent 审批")
    @Audit(operationType = OperationType.UPDATE, description = "提交 IQC Agent 审批", module = "IQC_AGENT")
    public QualityAgent submitAgent(@PathVariable String id) { return agentService.submit(id); }

    @PostMapping("/agents/{id}/approve")
    @ResourcePermission(code = "iqc:agent:approve", name = "审批 Agent", type = "iqc", description = "审批通过 Agent")
    @Audit(operationType = OperationType.UPDATE, description = "审批通过 IQC Agent", module = "IQC_AGENT")
    public QualityAgent publishAgent(@PathVariable String id) { return agentService.publish(id); }

    @PostMapping("/agents/{id}/reject")
    @ResourcePermission(code = "iqc:agent:approve", name = "驳回 Agent", type = "iqc", description = "驳回 Agent")
    @Audit(operationType = OperationType.UPDATE, description = "驳回 IQC Agent", module = "IQC_AGENT")
    public QualityAgent rejectAgent(@PathVariable String id) { return agentService.reject(id); }

    @PostMapping("/agents/{id}/disable")
    @ResourcePermission(code = "iqc:agent:manage", name = "停用 Agent", type = "iqc", description = "停用已发布 Agent")
    @Audit(operationType = OperationType.UPDATE, description = "停用 IQC Agent", module = "IQC_AGENT")
    public QualityAgent disableAgent(@PathVariable String id) { return agentService.disable(id); }

    @GetMapping("/agents/{id}/versions")
    @ResourcePermission(code = "iqc:agent:view", name = "查看 Agent 版本", type = "iqc", description = "查询 Agent 版本")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC Agent 版本", module = "IQC_AGENT")
    public List<QualityAgentVersion> agentVersions(@PathVariable String id) { return agentService.versions(id); }

    @PostMapping("/agents/{id}/versions")
    @ResourcePermission(code = "iqc:agent:manage", name = "创建 Agent 版本", type = "iqc", description = "创建 Agent 版本")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC Agent 版本", module = "IQC_AGENT")
    public QualityAgentVersion createAgentVersion(@PathVariable String id, @RequestBody AgentRequest request) { return agentService.createVersion(id, request.name(), request.code(), request.description(), request.configJson()); }

    @PostMapping("/agents/{id}/versions/{versionNo}/rollback")
    @ResourcePermission(code = "iqc:agent:manage", name = "回滚 Agent 版本", type = "iqc", description = "基于历史版本创建新草稿")
    @Audit(operationType = OperationType.UPDATE, description = "回滚 IQC Agent 版本", module = "IQC_AGENT")
    public QualityAgentVersion rollbackAgent(@PathVariable String id, @PathVariable int versionNo) { return agentService.rollback(id, versionNo); }

    @GetMapping("/agents/{id}/versions/compare")
    @ResourcePermission(code = "iqc:agent:view", name = "比较 Agent 版本", type = "iqc", description = "比较 Agent 版本配置差异")
    @Audit(operationType = OperationType.QUERY, description = "比较 IQC Agent 版本", module = "IQC_AGENT")
    public QualityAgentService.AgentVersionComparison compareAgentVersions(@PathVariable String id,
            @RequestParam int fromVersion, @RequestParam int toVersion) {
        return agentService.compare(id, fromVersion, toVersion);
    }

    @GetMapping("/agents/{id}/versions/{versionNo}/effect")
    @ResourcePermission(code = "iqc:agent:view", name = "查看 Agent 版本效果", type = "iqc", description = "查询 Agent 版本历史效果")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC Agent 版本效果", module = "IQC_AGENT")
    public AgentEffectService.AgentEffectReport agentEffect(@PathVariable String id, @PathVariable int versionNo) {
        return agentEffectService.report(id, versionNo);
    }

    @GetMapping("/rules")
    @ResourcePermission(code = "iqc:rule:view", name = "查看规则", type = "iqc", description = "查询质检规则")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC 规则", module = "IQC_RULE")
    public List<QualityRule> rules() { return ruleService.list(); }

    @PostMapping("/rules")
    @ResourcePermission(code = "iqc:rule:manage", name = "管理规则", type = "iqc", description = "创建质检规则")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC 规则", module = "IQC_RULE")
    @RateLimit(sceneCode = "iqc-rule-create", maxCount = 30, period = 60)
    public QualityRule createRule(@RequestBody RuleRequest request) { return ruleService.create(request.name(), request.code(), request.category(), request.ruleType(), request.targetRole(), request.expression(), request.description(), request.deduction(), request.riskLevel(), request.veto()); }

    @PostMapping("/rules/{id}/submit")
    @ResourcePermission(code = "iqc:rule:manage", name = "提交规则审批", type = "iqc", description = "提交规则审批")
    @Audit(operationType = OperationType.UPDATE, description = "提交 IQC 规则审批", module = "IQC_RULE")
    public QualityRule submitRule(@PathVariable String id) { return ruleService.submit(id); }

    @PostMapping("/rules/{id}/approve")
    @ResourcePermission(code = "iqc:rule:approve", name = "审批规则", type = "iqc", description = "审批通过规则")
    @Audit(operationType = OperationType.UPDATE, description = "审批通过 IQC 规则", module = "IQC_RULE")
    public QualityRule publishRule(@PathVariable String id) { return ruleService.publish(id); }

    @PostMapping("/rules/{id}/reject")
    @ResourcePermission(code = "iqc:rule:approve", name = "驳回规则", type = "iqc", description = "驳回规则")
    @Audit(operationType = OperationType.UPDATE, description = "驳回 IQC 规则", module = "IQC_RULE")
    public QualityRule rejectRule(@PathVariable String id) { return ruleService.reject(id); }

    @GetMapping("/rules/{id}/versions")
    @ResourcePermission(code = "iqc:rule:view", name = "查看规则版本", type = "iqc", description = "查询规则版本")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC 规则版本", module = "IQC_RULE")
    public List<QualityRuleVersion> ruleVersions(@PathVariable String id) { return ruleService.versions(id); }

    @PostMapping("/rules/{id}/versions")
    @ResourcePermission(code = "iqc:rule:manage", name = "创建规则版本", type = "iqc", description = "创建规则版本")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC 规则版本", module = "IQC_RULE")
    public QualityRuleVersion createRuleVersion(@PathVariable String id, @RequestBody RuleRequest request) { return ruleService.createVersion(id, request.name(), request.code(), request.category(), request.ruleType(), request.targetRole(), request.expression(), request.description(), request.deduction(), request.riskLevel(), request.veto()); }

    @PostMapping("/rules/{id}/test")
    @ResourcePermission(code = "iqc:rule:test", name = "测试规则", type = "iqc", description = "测试质检规则")
    @Audit(operationType = OperationType.QUERY, description = "测试 IQC 规则", module = "IQC_RULE")
    @RateLimit(sceneCode = "iqc-rule-test", maxCount = 60, period = 60)
    public QualityRuleService.RuleTestResult testRule(@PathVariable String id, @RequestBody RuleTestRequest request) {
        return ruleService.test(id, request.content());
    }

    @GetMapping("/rule-sets")
    @ResourcePermission(code = "iqc:rule:view", name = "查看规则集", type = "iqc", description = "查询质检规则集")
    public List<QualityRuleSet> ruleSets() { return ruleSetService.list(); }

    @PostMapping("/rule-sets")
    @ResourcePermission(code = "iqc:rule:manage", name = "管理规则集", type = "iqc", description = "创建质检规则集")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC 规则集", module = "IQC_RULE")
    public QualityRuleSet createRuleSet(@RequestBody RuleSetRequest request) { return ruleSetService.create(request.name(), request.code(), request.description(), request.ruleIds(), request.aggregationMode()); }

    @GetMapping("/rule-sets/{id}/versions")
    @ResourcePermission(code = "iqc:rule:view", name = "查看规则集版本", type = "iqc", description = "查询规则集版本")
    public List<QualityRuleSetVersion> ruleSetVersions(@PathVariable String id) { return ruleSetService.versions(id); }

    @PostMapping("/rule-sets/{id}/versions")
    @ResourcePermission(code = "iqc:rule:manage", name = "创建规则集版本", type = "iqc", description = "创建规则集版本")
    public QualityRuleSetVersion createRuleSetVersion(@PathVariable String id, @RequestBody RuleSetRequest request) { return ruleSetService.createVersion(id, request.name(), request.description(), request.ruleIds(), request.aggregationMode()); }

    @PostMapping("/rule-sets/{id}/submit")
    @ResourcePermission(code = "iqc:rule:manage", name = "提交规则集审批", type = "iqc", description = "提交规则集审批")
    public QualityRuleSet submitRuleSet(@PathVariable String id) { return ruleSetService.submit(id); }

    @PostMapping("/rule-sets/{id}/approve")
    @ResourcePermission(code = "iqc:rule:approve", name = "审批规则集", type = "iqc", description = "审批发布规则集")
    public QualityRuleSet publishRuleSet(@PathVariable String id) { return ruleSetService.publish(id); }

    @PostMapping("/rule-sets/{id}/reject")
    @ResourcePermission(code = "iqc:rule:approve", name = "驳回规则集", type = "iqc", description = "驳回规则集")
    public QualityRuleSet rejectRuleSet(@PathVariable String id) { return ruleSetService.reject(id); }

    public record AgentRequest(String name, String code, String description, String configJson) { }
    public record RuleRequest(String name, String code, String category, String ruleType, String targetRole, String expression, String description, Integer deduction, String riskLevel, Boolean veto) { }
    public record RuleTestRequest(String content) { }
    public record RuleSetRequest(String name, String code, String description, List<String> ruleIds, String aggregationMode) { }
}
