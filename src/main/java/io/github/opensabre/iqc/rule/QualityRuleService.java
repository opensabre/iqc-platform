package io.github.opensabre.iqc.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QualityRuleService {
    private final QualityRuleMapper mapper;
    private final QualityRuleVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public List<QualityRule> list() { return mapper.selectList(Wrappers.<QualityRule>lambdaQuery().orderByDesc(QualityRule::getCreatedTime)); }

    /** Returns the current rule for a stable business code, used by idempotent template materialization. */
    public QualityRule findByCode(String code) {
        if (code == null || code.isBlank()) return null;
        return mapper.selectOne(Wrappers.<QualityRule>lambdaQuery().eq(QualityRule::getCode, code.trim()).last("LIMIT 1"));
    }

    @Transactional
    public QualityRule create(String name, String code, String category, String ruleType, String targetRole, String expression, String description, Integer deduction, String riskLevel, Boolean veto) {
        if (name == null || name.isBlank() || code == null || code.isBlank()) throw IqcException.invalidArgument("规则名称和编码不能为空");
        validateExpression(ruleType == null ? "KEYWORD" : ruleType, expression);
        QualityRule rule = new QualityRule();
        rule.setName(name.trim()); rule.setCode(code.trim()); rule.setCategory(category == null || category.isBlank() ? "CUSTOM" : category.trim().toUpperCase()); rule.setRuleType(ruleType == null ? "KEYWORD" : ruleType); rule.setTargetRole(targetRole == null || targetRole.isBlank() ? "ALL" : targetRole.trim().toLowerCase()); rule.setExpression(expression); rule.setDescription(description);
        rule.setDeduction(deduction == null ? 10 : Math.max(0, Math.min(100, deduction)));
        rule.setRiskLevel(riskLevel == null || riskLevel.isBlank() ? "MEDIUM" : riskLevel.trim().toUpperCase());
        rule.setVeto(Boolean.TRUE.equals(veto)); rule.setStatus("DRAFT");
        mapper.insert(rule);
        rule.setVersionNo(1); mapper.updateById(rule);
        versionMapper.insert(toVersion(rule, 1, "DRAFT"));
        return rule;
    }

    @Transactional
    public QualityRule submit(String id) {
        QualityRule rule = mapper.selectById(id);
        if (rule == null) throw IqcException.notFound("规则不存在: " + id);
        QualityRuleVersion version = latestVersion(id);
        if (version == null) { version = toVersion(rule, rule.getVersionNo() == null ? 1 : rule.getVersionNo(), "DRAFT"); versionMapper.insert(version); }
        if (version.getExpression() == null || version.getExpression().isBlank()) throw IqcException.invalidArgument("规则表达式不能为空");
        validateExpression(version.getRuleType(), version.getExpression());
        if ("PUBLISHED".equals(version.getStatus())) throw IqcException.invalidState("已发布版本不能重复提交审批");
        version.setStatus("PENDING_APPROVAL"); versionMapper.updateById(version); rule.setStatus("PENDING_APPROVAL"); mapper.updateById(rule); return rule;
    }

    @Transactional
    public QualityRule publish(String id) {
        QualityRule rule = mapper.selectById(id);
        if (rule == null) throw IqcException.notFound("规则不存在: " + id);
        QualityRuleVersion version = latestVersion(id);
        if (version == null || !"PENDING_APPROVAL".equals(version.getStatus())) throw IqcException.invalidState("规则必须先提交审批");
        version.setStatus("PUBLISHED"); versionMapper.updateById(version);
        copyVersion(rule, version); rule.setStatus("PUBLISHED"); mapper.updateById(rule); return rule;
    }

    @Transactional
    public QualityRule reject(String id) {
        QualityRule rule = mapper.selectById(id);
        if (rule == null) throw IqcException.notFound("规则不存在: " + id);
        QualityRuleVersion version = latestVersion(id);
        if (version == null || !"PENDING_APPROVAL".equals(version.getStatus())) throw IqcException.invalidState("规则当前没有待审批版本");
        version.setStatus("REJECTED"); versionMapper.updateById(version);
        rule.setStatus(versions(id).stream().anyMatch(item -> "PUBLISHED".equals(item.getStatus())) ? "PUBLISHED" : "DRAFT");
        mapper.updateById(rule); return rule;
    }

    public List<QualityRuleVersion> versions(String ruleId) {
        return versionMapper.selectList(Wrappers.<QualityRuleVersion>lambdaQuery().eq(QualityRuleVersion::getRuleId, ruleId).orderByDesc(QualityRuleVersion::getVersionNo));
    }

    @Transactional
    public QualityRuleVersion createVersion(String ruleId, String name, String code, String category, String ruleType, String targetRole, String expression, String description, Integer deduction, String riskLevel, Boolean veto) {
        QualityRule rule = mapper.selectById(ruleId);
        if (rule == null) throw IqcException.notFound("规则不存在: " + ruleId);
        int next = versions(ruleId).stream().map(QualityRuleVersion::getVersionNo).filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        QualityRuleVersion version = new QualityRuleVersion(); version.setRuleId(ruleId); version.setVersionNo(next); version.setName(name == null ? rule.getName() : name.trim()); version.setCode(code == null ? rule.getCode() : code.trim()); version.setCategory(category == null || category.isBlank() ? rule.getCategory() : category.trim().toUpperCase());
        version.setRuleType(ruleType == null ? rule.getRuleType() : ruleType); version.setTargetRole(targetRole == null || targetRole.isBlank() ? rule.getTargetRole() : targetRole.trim().toLowerCase()); version.setExpression(expression); version.setDescription(description); version.setDeduction(deduction == null ? 10 : Math.max(0, Math.min(100, deduction)));
        validateExpression(version.getRuleType(), version.getExpression());
        version.setRiskLevel(riskLevel == null || riskLevel.isBlank() ? "MEDIUM" : riskLevel.trim().toUpperCase()); version.setVeto(Boolean.TRUE.equals(veto)); version.setStatus("DRAFT"); versionMapper.insert(version);
        return version;
    }

    private void validateExpression(String ruleType, String expression) {
        try {
            RuleMatcher.validate(ruleType, expression, objectMapper);
        } catch (IllegalArgumentException exception) {
            throw IqcException.invalidArgument("规则表达式无效: " + exception.getMessage(), exception);
        }
    }

    private QualityRuleVersion latestVersion(String ruleId) { return versions(ruleId).stream().findFirst().orElse(null); }

    private QualityRuleVersion toVersion(QualityRule rule, int versionNo, String status) {
        QualityRuleVersion version = new QualityRuleVersion(); version.setRuleId(rule.getId()); version.setVersionNo(versionNo); version.setName(rule.getName()); version.setCode(rule.getCode()); version.setCategory(rule.getCategory()); version.setRuleType(rule.getRuleType()); version.setTargetRole(rule.getTargetRole()); version.setExpression(rule.getExpression()); version.setDescription(rule.getDescription()); version.setDeduction(rule.getDeduction()); version.setRiskLevel(rule.getRiskLevel()); version.setVeto(rule.getVeto()); version.setStatus(status); return version;
    }

    private void copyVersion(QualityRule rule, QualityRuleVersion version) { rule.setName(version.getName()); rule.setCode(version.getCode()); rule.setCategory(version.getCategory()); rule.setRuleType(version.getRuleType()); rule.setTargetRole(version.getTargetRole()); rule.setExpression(version.getExpression()); rule.setDescription(version.getDescription()); rule.setDeduction(version.getDeduction()); rule.setRiskLevel(version.getRiskLevel()); rule.setVeto(version.getVeto()); rule.setVersionNo(version.getVersionNo()); }

    public RuleTestResult test(String id, String content) {
        QualityRule rule = mapper.selectById(id);
        if (rule == null) throw IqcException.notFound("规则不存在: " + id);
        if (content == null || content.isBlank()) throw IqcException.invalidArgument("测试文本不能为空");
        String expression = rule.getExpression();
        if (expression == null || expression.isBlank()) throw IqcException.invalidArgument("规则表达式不能为空");
        if ("LLM".equalsIgnoreCase(rule.getRuleType()))
            return new RuleTestResult(false, "NOT_SUPPORTED", null, "LLM 规则请在质检沙盒中选择模型测试");
        try {
            var message = new io.github.opensabre.iqc.conversation.model.ConversationMessage();
            message.setContent(content);
            RuleMatcher.Match match = RuleMatcher.evaluate(objectMapper, rule.getRuleType(), expression, message);
            return new RuleTestResult(match.hit(), match.hit() ? "HIT" : "NOT_HIT", match.text(),
                    match.hit() ? "命中规则" : "未命中规则");
        } catch (IllegalArgumentException exception) {
            String prefix = rule.getRuleType() != null && rule.getRuleType().toUpperCase().contains("REGEX")
                    ? "正则表达式无效: " : "规则表达式无效: ";
            return new RuleTestResult(false, "ERROR", null, prefix + exception.getMessage());
        }
    }

    public record RuleTestResult(boolean matched, String resultStatus, String matchedText, String reason) { }
}
