package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.InsightLabel;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LabelTaxonomyServiceTest {
    private final LabelCategoryMapper categories = mock(LabelCategoryMapper.class);
    private final LabelGroupMapper groups = mock(LabelGroupMapper.class);
    private final InsightLabelMapper labels = mock(InsightLabelMapper.class);
    private final LabelValueDefinitionMapper values = mock(LabelValueDefinitionMapper.class);
    private final LabelRuleBindingMapper bindings = mock(LabelRuleBindingMapper.class);
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final LabelTaxonomyService service = new LabelTaxonomyService(categories, groups, labels, values, bindings, rules, scope, new ObjectMapper());

    @BeforeEach
    void allowCurrentScope() {
        when(scope.canView(any(), any())).thenReturn(true);
    }

    @Test
    void rejectsMoreThanTenValues() {
        when(labels.selectById("label-1")).thenReturn(new InsightLabel());
        List<LabelTaxonomyService.ValueRequest> requests = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> new LabelTaxonomyService.ValueRequest("v" + i, "FIXED", null, null)).toList();
        assertThatThrownBy(() -> service.replaceValues("label-1", requests)).isInstanceOf(IqcException.class);
    }

    @Test
    void rejectsPercentageDefaultOutsideRange() {
        when(labels.selectById("label-1")).thenReturn(new InsightLabel());
        assertThatThrownBy(() -> service.replaceValues("label-1", List.of(
                new LabelTaxonomyService.ValueRequest("ratio", "PERCENTAGE", null, "{\"defaultValue\":101}"))))
                .isInstanceOf(IqcException.class).hasMessageContaining("PERCENTAGE");
    }

    @Test
    void labelWithoutPublishedRuleBindingCannotPublish() {
        InsightLabel label = new InsightLabel(); label.setId("label-1");
        when(labels.selectById("label-1")).thenReturn(label);
        when(bindings.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.publish("label-1")).isInstanceOf(IqcException.class);
    }

    @Test
    void detailRejectsLabelOutsideCurrentDataScope() {
        InsightLabel label = new InsightLabel(); label.setId("label-1"); label.setCreatedBy("other");
        when(labels.selectById("label-1")).thenReturn(label);
        when(scope.canView("other", null)).thenReturn(false);

        assertThatThrownBy(() -> service.detail("label-1")).isInstanceOf(IqcException.class).hasMessageContaining("无权");
    }
}
