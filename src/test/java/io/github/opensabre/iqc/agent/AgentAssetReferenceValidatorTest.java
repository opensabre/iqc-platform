package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import io.github.opensabre.iqc.skill.dao.IqcSkillMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentAssetReferenceValidatorTest {
    @Test
    void rejectsDisabledPrimaryModel() {
        IqcModelProfileMapper models=mock(IqcModelProfileMapper.class);
        IqcModelProfile profile=new IqcModelProfile(); profile.setStatus("DISABLED");
        when(models.selectById("model-1")).thenReturn(profile);
        AgentConfiguration config=new AgentConfiguration("2.0","prompt",null,null,null,null,"model-1",List.of(),List.of(),List.of(),null);
        var validator=new AgentAssetReferenceValidator(models,mock(IqcMcpServerMapper.class),mock(IqcSkillMapper.class));
        assertThatThrownBy(()->validator.validate(config)).isInstanceOf(IqcException.class).hasMessageContaining("已停用");
    }

    @Test
    void ruleOnlyCreatesEmptyAssetSnapshotWithoutResolvingAssets() {
        IqcModelProfileMapper models=mock(IqcModelProfileMapper.class);
        IqcMcpServerMapper mcps=mock(IqcMcpServerMapper.class);
        IqcSkillMapper skills=mock(IqcSkillMapper.class);
        AgentConfiguration config=new AgentConfiguration("2.0","RULE_ONLY","",null,null,null,null,
                null,List.of(),List.of(),List.of(),null);

        AgentConfiguration.AssetSnapshots snapshot=new AgentAssetReferenceValidator(models,mcps,skills).snapshot(config);

        assertThat(snapshot.primaryModel()).isNull();
        assertThat(snapshot.fallbackModels()).isEmpty();
        assertThat(snapshot.mcpServers()).isEmpty();
        assertThat(snapshot.skills()).isEmpty();
        verifyNoInteractions(models,mcps,skills);
    }
}
