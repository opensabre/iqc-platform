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
        when(labels.selectOne(any())).thenReturn(new InsightLabel());
    }

    @Test
    void ruleLocksUseStableOrderWhileSavedBindingsKeepUserOrder() {
        when(labels.selectById("label")).thenReturn(new InsightLabel());
        when(rules.lockById("a")).thenReturn(rule("a", "REGEX"));
        when(rules.lockById("z")).thenReturn(rule("z", "LLM"));
        service.replaceBindings("label", List.of(new LabelTaxonomyService.BindingRequest("z"), new LabelTaxonomyService.BindingRequest("a")));
        var locks = inOrder(rules); locks.verify(rules).lockById("a"); locks.verify(rules).lockById("z");
        var saved = org.mockito.ArgumentCaptor.forClass(io.github.opensabre.iqc.label.model.LabelRuleBinding.class);
        verify(bindings, times(2)).insert(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getAllValues()).extracting(io.github.opensabre.iqc.label.model.LabelRuleBinding::getRuleId)
                .containsExactly("z", "a");
    }

    @Test
    void explicitEmptyBindingsCreateDraftRevisionAndDoNotResolveRules() {
        var label = new InsightLabel(); label.setId("label"); label.setStatus("PUBLISHED"); label.setVersionNo(3);
        when(labels.selectById("label")).thenReturn(label); when(labels.selectOne(any())).thenReturn(label);
        when(bindings.selectList(any())).thenReturn(List.of());
        service.replaceBindings("label", List.of());
        org.assertj.core.api.Assertions.assertThat(label.getStatus()).isEqualTo("DRAFT");
        org.assertj.core.api.Assertions.assertThat(label.getVersionNo()).isEqualTo(4);
        verify(labels).updateById(label); verify(bindings).delete(any()); verifyNoInteractions(rules);
        assertThatThrownBy(() -> service.publish("label")).hasMessageContaining("绑定已发布规则");
    }

    @Test
    void omittedBindingsDoNotAccidentallyClearExistingRules() {
        when(labels.selectById("label")).thenReturn(new InsightLabel());
        assertThatThrownBy(() -> service.replaceBindings("label", null)).hasMessageContaining("数组");
        verify(bindings, never()).delete(any()); verify(labels, never()).updateById(any(InsightLabel.class));
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
    void explicitRuleHitValuesMustMatchTheDeclaredType() {
        when(labels.selectById("label-1")).thenReturn(new InsightLabel());
        assertThatThrownBy(() -> service.replaceValues("label-1", List.of(
                new LabelTaxonomyService.ValueRequest("owns", "BOOLEAN", null, "{\"onRuleHit\":{\"rule-1\":\"false\"}}"))))
                .isInstanceOf(IqcException.class).hasMessageContaining("BOOLEAN");
        service.replaceValues("label-1", List.of(
                new LabelTaxonomyService.ValueRequest("owns", "BOOLEAN", null, "{\"onRuleHit\":{\"rule-1\":false}}")));
        verify(values).insert(argThat((io.github.opensabre.iqc.label.model.LabelValueDefinition value) ->
                "{\"onRuleHit\":{\"rule-1\":false}}".equals(value.getConfigJson())));
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
    @Test
    void sameTypeRulesAreRejectedBeforeAnyBindingOrLabelIsChanged() {
        when(labels.selectById("label")).thenReturn(new InsightLabel());
        var first = rule("r1", "REGEX"); var second = rule("r2", "REGEX");
        when(rules.lockById("r1")).thenReturn(first); when(rules.lockById("r2")).thenReturn(second);
        assertThatThrownBy(() -> service.replaceBindings("label", List.of(
                new LabelTaxonomyService.BindingRequest("r1"), new LabelTaxonomyService.BindingRequest("r2"))))
                .isInstanceOf(IqcException.class).hasMessageContaining("最多绑定一条");
        verifyNoInteractions(bindings); verify(labels, never()).updateById(any(InsightLabel.class));
    }
    @Test
    void oneRuleCanBeReusedByLabelsAndEachBindingStoresItsTypeAndVersion() {
        when(labels.selectById(anyString())).thenReturn(new InsightLabel());
        when(rules.lockById("r1")).thenReturn(rule("r1", "REGEX"));
        when(rules.lockById("r2")).thenReturn(rule("r2", "LLM"));
        when(bindings.selectList(any())).thenReturn(List.of());
        service.replaceBindings("label1", List.of(new LabelTaxonomyService.BindingRequest("r1"), new LabelTaxonomyService.BindingRequest("r2")));
        service.replaceBindings("label2", List.of(new LabelTaxonomyService.BindingRequest("r1")));
        verify(bindings).insert(argThat((io.github.opensabre.iqc.label.model.LabelRuleBinding b) -> "label1".equals(b.getLabelId()) && "r1".equals(b.getRuleId()) && "REGEX".equals(b.getRuleType()) && b.getRuleVersionNo() == 2));
        verify(bindings).insert(argThat((io.github.opensabre.iqc.label.model.LabelRuleBinding b) -> "label2".equals(b.getLabelId()) && "r1".equals(b.getRuleId())));
    }
    private io.github.opensabre.iqc.rule.model.QualityRule rule(String id, String type) {
        var r = new io.github.opensabre.iqc.rule.model.QualityRule(); r.setId(id); r.setRuleType(type); r.setVersionNo(2); r.setStatus("PUBLISHED"); return r;
    }

}
