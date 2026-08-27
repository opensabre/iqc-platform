package io.github.opensabre.iqc.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleSetMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleSetVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleSet;
import io.github.opensabre.iqc.rule.model.QualityRuleSetVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityRuleSetServiceTest {
    private final QualityRuleSetMapper mapper = mock(QualityRuleSetMapper.class);
    private final QualityRuleSetVersionMapper versionMapper = mock(QualityRuleSetVersionMapper.class);
    private final QualityRuleMapper ruleMapper = mock(QualityRuleMapper.class);
    private final QualityRuleSetService service = new QualityRuleSetService(mapper, versionMapper, ruleMapper, new ObjectMapper());

    @Test
    void publishedSetReturnsOrderedRuleIds() {
        QualityRuleSet set = new QualityRuleSet(); set.setId("set-1"); set.setStatus("PUBLISHED"); set.setRuleIdsJson("[\"rule-2\",\"rule-1\"]");
        QualityRule published = new QualityRule(); published.setStatus("PUBLISHED");
        when(mapper.selectById("set-1")).thenReturn(set); when(ruleMapper.selectById(any())).thenReturn(published);

        assertThat(service.publishedRuleIds("set-1")).containsExactly("rule-2", "rule-1");
    }

    @Test
    void submissionRejectsDraftMemberRules() {
        QualityRuleSet set = new QualityRuleSet(); set.setId("set-1"); set.setStatus("DRAFT");
        QualityRuleSetVersion version = new QualityRuleSetVersion(); version.setRuleSetId("set-1"); version.setStatus("DRAFT"); version.setRuleIdsJson("[\"rule-1\"]");
        QualityRule draft = new QualityRule(); draft.setStatus("DRAFT");
        when(mapper.selectById("set-1")).thenReturn(set); when(versionMapper.selectList(any())).thenReturn(List.of(version)); when(ruleMapper.selectById("rule-1")).thenReturn(draft);

        assertThatThrownBy(() -> service.submit("set-1")).isInstanceOf(IqcException.class).hasMessageContaining("只能引用已发布规则");
    }

    @Test
    void ruleSetCannotContainMoreThanOneHundredRules() {
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(value -> "rule-" + value).toList();
        assertThatThrownBy(() -> service.create("全量规则", "ALL_RULES", null, ids, "ALL"))
                .isInstanceOf(IqcException.class).hasMessageContaining("最多包含 100 条");
    }
}
