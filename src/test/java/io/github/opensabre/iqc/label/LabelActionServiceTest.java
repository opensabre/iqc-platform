package io.github.opensabre.iqc.label;

import io.github.opensabre.iqc.label.dao.*;
import io.github.opensabre.iqc.label.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LabelActionServiceTest {
    final LabelActionDefinitionMapper definitions=mock(LabelActionDefinitionMapper.class);
    final LabelActionBindingMapper bindings=mock(LabelActionBindingMapper.class);
    final InsightLabelMapper labels=mock(InsightLabelMapper.class);
    final LabelTaxonomyService taxonomy=mock(LabelTaxonomyService.class);
    final IqcDataScope scope=mock(IqcDataScope.class);
    final LabelActionService service=new LabelActionService(definitions,bindings,labels,taxonomy,scope);
    @BeforeEach void setup(){when(scope.canView(any(),any())).thenReturn(true);when(labels.selectById("label")).thenReturn(new InsightLabel());}
    LabelActionDefinition action(){var a=new LabelActionDefinition();a.setId(9007199254740993L);a.setType("DEDUCT_SCORE");a.setStatus("ENABLED");a.setVersionNo(1);a.setName("扣分动作定义");return a;}
    @Test void duplicateBindingsAreRejectedBeforeRevisionOrWrites(){
        assertThatThrownBy(()->service.replace("label",List.of(1L,1L))).hasMessageContaining("不重复");
        verifyNoInteractions(taxonomy,definitions,bindings);
    }
    @Test void referencedActionCannotBeDisabledOrDeleted(){
        when(definitions.selectOne(any())).thenReturn(action());when(bindings.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(()->service.disableOrDelete(9007199254740993L,false)).hasMessageContaining("仍被标签引用");
        assertThatThrownBy(()->service.disableOrDelete(9007199254740993L,true)).hasMessageContaining("仍被标签引用");
        verify(definitions,never()).deleteById(anyLong());verify(definitions,never()).updateById(any(LabelActionDefinition.class));
    }
    @Test void snapshotsAreDetachedFromLaterDefinitionEditsAndKeepSnowflakePrecision(){
        var action=action();when(definitions.selectOne(any())).thenReturn(action);
        var binding=new LabelActionBinding();binding.setId(2L);binding.setActionId(action.getId());binding.setLabelId("label");
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        var snapshot=service.snapshots("label");action.setName("修订后");action.setVersionNo(2);
        assertThat(snapshot.getFirst().actionId()).isEqualTo("9007199254740993");
        assertThat(snapshot.getFirst().name()).isEqualTo("扣分动作定义");assertThat(snapshot.getFirst().versionNo()).isEqualTo(1);
    }
    @Test void unavailableOrOutOfScopeActionsCannotBeBound(){
        var action=action();action.setStatus("DISABLED");when(definitions.selectOne(any())).thenReturn(action);
        assertThatThrownBy(()->service.replace("label",List.of(action.getId()))).hasMessageContaining("停用");
        action.setStatus("ENABLED");action.setCreatedBy("other");when(scope.canView("other",null)).thenReturn(false);
        assertThatThrownBy(()->service.replace("label",List.of(action.getId()))).hasMessageContaining("权限");
        verifyNoInteractions(taxonomy);verify(bindings,never()).insert(any(LabelActionBinding.class));
    }
    @Test void replacementRevisesLabelAndAllowsAnExplicitEmptySet(){
        when(bindings.selectList(any())).thenReturn(List.of());service.replace("label",List.of());
        verify(taxonomy).reviseBindings("label");verify(bindings).delete(any());
    }
    @Test void actionTypesDoNotIntroduceScoringParameters(){
        assertThatThrownBy(()->service.save(null,new LabelActionService.Request("AUTO_SCORE","x",null))).hasMessageContaining("无效");
        var saved=service.save(null,new LabelActionService.Request("DEDUCT_SCORE","待对接",null));
        assertThat(saved.getVersionNo()).isEqualTo(1);assertThat(saved.getStatus()).isEqualTo("ENABLED");
        verify(definitions).insert(any(LabelActionDefinition.class));
    }
}
