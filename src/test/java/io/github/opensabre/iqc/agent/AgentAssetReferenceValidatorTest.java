package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import io.github.opensabre.iqc.skill.dao.IqcSkillMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
