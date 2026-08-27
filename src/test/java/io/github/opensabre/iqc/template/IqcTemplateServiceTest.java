package io.github.opensabre.iqc.template;

import io.github.opensabre.iqc.rule.QualityRuleService;
import io.github.opensabre.iqc.rule.model.QualityRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IqcTemplateServiceTest {

    @Test
    void materializesTwelveCommonNegativeRulesAsDraftCandidates() {
        QualityRuleService ruleService = mock(QualityRuleService.class);
        when(ruleService.create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            QualityRule rule = new QualityRule();
            rule.setCode(invocation.getArgument(1));
            rule.setStatus("DRAFT");
            return rule;
        });
        IqcTemplateService service = new IqcTemplateService(ruleService);

        var result = service.materialize("NEGATIVE_COMMON_V1");

        assertThat(result.total()).isEqualTo(12);
        assertThat(result.created()).isEqualTo(12);
        assertThat(result.existing()).isZero();
        assertThat(result.rules()).extracting(QualityRule::getCode)
                .contains("NEG_INSULT_ABUSE", "NEG_FALSE_PROMISE", "NEG_PRIVACY_DISCLOSURE", "NEG_RISK_CONCEALMENT");
    }

    @Test
    void keepsExistingRuleInsteadOfCreatingDuplicate() {
        QualityRuleService ruleService = mock(QualityRuleService.class);
        QualityRule existing = new QualityRule();
        existing.setCode("NEG_INSULT_ABUSE");
        when(ruleService.findByCode("NEG_INSULT_ABUSE")).thenReturn(existing);
        when(ruleService.create(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(new QualityRule());
        IqcTemplateService service = new IqcTemplateService(ruleService);

        var result = service.materialize("NEGATIVE_COMMON_V1");

        assertThat(result.created()).isEqualTo(11);
        assertThat(result.existing()).isEqualTo(1);
        assertThat(result.rules()).contains(existing);
    }
}
