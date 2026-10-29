package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper;
import io.github.opensabre.iqc.mcp.model.IqcMcpServer;
import io.github.opensabre.iqc.skill.dao.IqcSkillMapper;
import io.github.opensabre.iqc.skill.model.IqcSkill;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Detection checks availability independently of unrelated Agent model configurations. */
class DetectionCapabilityAvailabilityTest {
    private final IqcSkillMapper skills=mock(IqcSkillMapper.class);
    private final IqcMcpServerMapper mcps=mock(IqcMcpServerMapper.class);
    private final IqcModelProfileMapper models=mock(IqcModelProfileMapper.class);
    private final AgentAssetReferenceValidator validator=new AgentAssetReferenceValidator(models,mcps,skills);
    @Test void enabledNewerAssetsDoNotRequireReplacingHistoricalVersion() {
        var skill=new IqcSkill();skill.setStatus("ENABLED");skill.setVersionNo(99);
        var server=new IqcMcpServer();server.setStatus("ENABLED");server.setVersionNo(100);
        when(skills.selectById("skill")).thenReturn(skill);when(mcps.selectById("server")).thenReturn(server);
        validator.validateDetectionCapabilities("skill","server");verifyNoInteractions(models);
    }
    @Test void disabledSkillRejectsBeforeMcpLookup() {
        var skill=new IqcSkill();skill.setStatus("DISABLED");when(skills.selectById("skill")).thenReturn(skill);
        assertThatThrownBy(()->validator.validateDetectionCapabilities("skill","server")).hasMessageContaining("Skill 不存在或已停用");
        verifyNoInteractions(mcps,models);
    }
    @Test void missingOrDisabledMcpRejectsEnabledSkill() {
        var skill=new IqcSkill();skill.setStatus("ENABLED");when(skills.selectById("skill")).thenReturn(skill);
        assertThatThrownBy(()->validator.validateDetectionCapabilities("skill","server")).hasMessageContaining("MCP 不存在或已停用");
        var server=new IqcMcpServer();server.setStatus("DISABLED");when(mcps.selectById("server")).thenReturn(server);
        assertThatThrownBy(()->validator.validateDetectionCapabilities("skill","server")).hasMessageContaining("MCP 不存在或已停用");
        verifyNoInteractions(models);
    }
    @Test void inferenceRetainsFrozenIdentityWhileCheckingModelAndMcpAvailability() {
        var model=new io.github.opensabre.iqc.modelprofile.model.IqcModelProfile();model.setStatus("ENABLED");model.setVersionNo(99);
        var server=new IqcMcpServer();server.setStatus("ENABLED");server.setVersionNo(100);
        when(models.selectById("model")).thenReturn(model);when(mcps.selectById("server")).thenReturn(server);
        validator.validateInferenceCapabilities("model","server");verifyNoInteractions(skills);
        model.setStatus("DISABLED");
        assertThatThrownBy(()->validator.validateInferenceCapabilities("model","server")).hasMessageContaining("模型配置不存在或已停用");
    }
}
