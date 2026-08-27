package io.github.opensabre.iqc.rule;

import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityRuleServiceTest {
    private final QualityRuleMapper mapper = mock(QualityRuleMapper.class);
    private final QualityRuleVersionMapper versionMapper = mock(QualityRuleVersionMapper.class);
    private final QualityRuleService service = new QualityRuleService(mapper, versionMapper, new ObjectMapper());

    @Test
    void keywordTestReturnsTheMatchedKeyword() {
        QualityRule rule = rule("KEYWORD", "减免|优惠");
        when(mapper.selectById("rule-1")).thenReturn(rule);

        QualityRuleService.RuleTestResult result = service.test("rule-1", "今天可以申请减免");

        assertThat(result.matched()).isTrue();
        assertThat(result.resultStatus()).isEqualTo("HIT");
        assertThat(result.matchedText()).isEqualTo("减免");
    }

    @Test
    void invalidRegexReturnsDiagnosticResult() {
        QualityRule rule = rule("REGEX", "[");
        when(mapper.selectById("rule-1")).thenReturn(rule);

        QualityRuleService.RuleTestResult result = service.test("rule-1", "文本");

        assertThat(result.matched()).isFalse();
        assertThat(result.resultStatus()).isEqualTo("ERROR");
        assertThat(result.reason()).contains("正则表达式无效");
    }

    @Test
    void structuredConditionTestEvaluatesJsonExpression() {
        QualityRule rule = rule("STRUCTURED", "{\"all\":[{\"field\":\"content\",\"operator\":\"contains\",\"value\":\"优惠\"}]}");
        when(mapper.selectById("rule-1")).thenReturn(rule);

        QualityRuleService.RuleTestResult result = service.test("rule-1", "今天有优惠");

        assertThat(result.matched()).isTrue();
        assertThat(result.resultStatus()).isEqualTo("HIT");
        assertThat(result.matchedText()).isEqualTo("优惠");
    }

    @Test
    void forbiddenContainsHitsWhenForbiddenPhraseAppears() {
        QualityRule rule = rule("FORBIDDEN_CONTAINS", "保证收益|绝对安全");
        when(mapper.selectById("rule-1")).thenReturn(rule);

        QualityRuleService.RuleTestResult result = service.test("rule-1", "这个产品保证收益");

        assertThat(result.matched()).isTrue();
        assertThat(result.matchedText()).isEqualTo("保证收益");
    }

    @Test
    void requiredContainsHitsWhenMandatoryPhraseIsMissing() {
        QualityRule rule = rule("REQUIRED_CONTAINS", "风险提示|请谨慎决策");
        when(mapper.selectById("rule-1")).thenReturn(rule);

        assertThat(service.test("rule-1", "只介绍了产品收益").matched()).isTrue();
        assertThat(service.test("rule-1", "请先阅读风险提示").matched()).isFalse();
    }

    @Test
    void compositeRuleSupportsNestedAllAnyAndNot() {
        QualityRule rule = rule("COMPOSITE", "{\"all\":[{\"field\":\"content\",\"operator\":\"contains\",\"value\":\"收益\"},{\"not\":{\"field\":\"content\",\"operator\":\"contains\",\"value\":\"风险\"}}]}");
        when(mapper.selectById("rule-1")).thenReturn(rule);

        assertThat(service.test("rule-1", "收益很高").matched()).isTrue();
        assertThat(service.test("rule-1", "收益与风险并存").matched()).isFalse();
    }

    @Test
    void invalidStructuredConditionCannotBeCreated() {
        assertThatThrownBy(() -> service.create("结构化规则", "structured-1", "CUSTOM", "STRUCTURED", "all",
                "{\"all\":[{\"field\":\"unknown\",\"operator\":\"equals\",\"value\":\"x\"}]}", null, 10, "MEDIUM", false))
                .isInstanceOf(IqcException.class)
                .hasMessageContaining("结构化条件字段");
    }

    @Test
    void ruleMustPassApprovalBeforeItCanBePublished() {
        QualityRule rule = rule("KEYWORD", "优惠");
        QualityRuleVersion version = new QualityRuleVersion();
        version.setRuleId("rule-1"); version.setVersionNo(1); version.setExpression("优惠"); version.setStatus("DRAFT");
        when(mapper.selectById("rule-1")).thenReturn(rule);
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(version));

        service.submit("rule-1");
        assertThat(version.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(rule.getStatus()).isEqualTo("PENDING_APPROVAL");

        service.publish("rule-1");
        assertThat(version.getStatus()).isEqualTo("PUBLISHED");
        assertThat(rule.getStatus()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> service.submit("rule-1"))
                .isInstanceOf(IqcException.class)
                .hasMessageContaining("不能重复提交");
        verify(versionMapper, org.mockito.Mockito.atLeastOnce()).updateById(version);
    }

    private QualityRule rule(String type, String expression) {
        QualityRule rule = new QualityRule();
        rule.setId("rule-1");
        rule.setRuleType(type);
        rule.setExpression(expression);
        return rule;
    }
}
