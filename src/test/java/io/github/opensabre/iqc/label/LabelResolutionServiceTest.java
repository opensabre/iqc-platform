package io.github.opensabre.iqc.label;

import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.InsightLabel;
import io.github.opensabre.iqc.label.model.LabelRuleBinding;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LabelResolutionServiceTest {
    private final LabelCategoryMapper categories = mock(LabelCategoryMapper.class);
    private final LabelGroupMapper groups = mock(LabelGroupMapper.class);
    private final InsightLabelMapper labels = mock(InsightLabelMapper.class);
    private final LabelRuleBindingMapper bindings = mock(LabelRuleBindingMapper.class);
    private final LabelValueDefinitionMapper values = mock(LabelValueDefinitionMapper.class);
    private final LabelCollectionMapper collections = mock(LabelCollectionMapper.class);
    private final LabelCollectionMemberMapper members = mock(LabelCollectionMemberMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final LabelResolutionService service = new LabelResolutionService(categories, groups, labels, bindings, values, collections, members, scope);

    @Test
    void deduplicatesSelectedLabelsAndSharedRules() {
        InsightLabel first = label("label-1", "a");
        InsightLabel second = label("label-2", "b");
        when(labels.selectBatchIds(any())).thenReturn(List.of(first, second));
        when(scope.canView(any(), any())).thenReturn(true);
        when(bindings.selectList(any())).thenReturn(List.of(binding("rule-shared")));
        when(values.selectList(any())).thenReturn(List.of());

        var resolved = service.resolve(new LabelResolutionService.LabelSelection(
                List.of(), List.of(), List.of("label-1", "label-1", "label-2"), List.of()));

        assertThat(resolved.labels()).extracting(LabelResolutionService.LabelSnapshot::id)
                .containsExactly("label-1", "label-2");
        assertThat(resolved.ruleIds()).containsExactly("rule-shared");
    }

    private InsightLabel label(String id, String code) {
        InsightLabel value = new InsightLabel();
        value.setId(id); value.setCode(code); value.setName(code); value.setStatus("PUBLISHED"); value.setVersionNo(1);
        return value;
    }

    @Test
    void versionedResolutionChecksPublishedIdentityAndBindingVersion() {
        var current = label("label-1", "a");
        var binding = binding("rule");
        when(labels.selectBatchIds(any())).thenReturn(List.of(current));
        when(scope.canView(any(), any())).thenReturn(true);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        var refs = List.of(new LabelResolutionService.LabelReference("label-1", 1));
        var resolved = service.resolveVersions(refs);
        assertThat(resolved.schemaVersion()).isEqualTo("2.0");
        assertThat(resolved.labels().getFirst().versionNo()).isEqualTo(1);
        current.setVersionNo(2);
        assertThatThrownBy(() -> service.resolveVersions(refs)).hasMessageContaining("版本已变化");
        current.setVersionNo(1); binding.setRuleVersionNo(null);
        assertThatThrownBy(() -> service.resolveVersions(refs)).hasMessageContaining("明确规则版本");
    }

    @Test
    void versionedResolutionRetainsScopeAndRejectsDuplicatesBeforeReading() {
        var ref = new LabelResolutionService.LabelReference("label-1", 1);
        assertThatThrownBy(() -> service.resolveVersions(List.of(ref, ref))).hasMessageContaining("重复");
        verifyNoInteractions(labels);
        when(labels.selectBatchIds(any())).thenReturn(List.of(label("label-1", "a")));
        assertThatThrownBy(() -> service.resolveVersions(List.of(ref))).hasMessageContaining("数据权限");
        verifyNoInteractions(bindings);
    }

    private LabelRuleBinding binding(String ruleId) {
        LabelRuleBinding value = new LabelRuleBinding(); value.setRuleId(ruleId); value.setRuleVersionNo(1); return value;
    }
}
