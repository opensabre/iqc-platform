package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.skill.dao.IqcSkillMapper;
import io.github.opensabre.iqc.skill.dao.IqcSkillVersionMapper;
import io.github.opensabre.iqc.skill.model.IqcSkill;
import io.github.opensabre.iqc.skill.model.IqcSkillVersion;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact Prompt references must remain independent of the mutable instruction head. */
class PromptAssetVersionTest {
    private final IqcSkillMapper skills=mock(IqcSkillMapper.class);
    private final IqcSkillVersionMapper versions=mock(IqcSkillVersionMapper.class);
    private final AgentAssetReferenceValidator validator=new AgentAssetReferenceValidator(
            mock(IqcModelProfileMapper.class),mock(IqcMcpServerMapper.class),skills,versions);
    private final ObjectMapper json=new ObjectMapper();

    private AgentConfiguration config(){return new AgentConfiguration("3.0",null,"",null,null,null,null,
            "model",List.of(),List.of(),List.of(),null,null)
            .withPrompt("",new AgentConfiguration.PromptReference("instruction",2,null));}
    private IqcSkillVersion seed(){
        var head=new IqcSkill();head.setId("instruction");head.setStatus("ENABLED");
        head.setVersionNo(9);head.setInstructions("new head text");
        when(skills.selectById("instruction")).thenReturn(head);
        var version=new IqcSkillVersion();version.setSkillId("instruction");version.setVersionNo(2);
        version.setStatus("ENABLED");version.setInstructions("historical instructions");
        when(versions.selectOne(any())).thenReturn(version);return version;
    }
    @Test void freezesExactHistoricalBodyAndLeavesSourceAndCallerUnchanged() throws Exception {
        var version=seed();var original=config();var frozen=validator.resolvePrompt(original);
        assertThat(frozen.systemPrompt()).isEqualTo("historical instructions");
        assertThat(frozen.promptAsset().versionNo()).isEqualTo(2);
        assertThat(frozen.promptAsset().contentHash()).hasSize(64);
        assertThat(original.systemPrompt()).isEmpty();assertThat(original.promptAsset().contentHash()).isNull();
        assertThat(version.getInstructions()).isEqualTo("historical instructions");
        validator.validatePrompt(frozen);
        assertThat(json.readValue(json.writeValueAsString(frozen),AgentConfiguration.class)).isEqualTo(frozen);
        version.setInstructions("tampered historical text");
        assertThatThrownBy(()->validator.validatePrompt(frozen)).hasMessageContaining("不一致");
    }
    @Test void rejectsDisabledMissingWrongIdentityAndOversizedHistoricalVersions(){
        var version=seed();version.setStatus("DISABLED");
        assertThatThrownBy(()->validator.resolvePrompt(config())).hasMessageContaining("版本");
        version.setStatus("ENABLED");version.setSkillId("other");
        assertThatThrownBy(()->validator.resolvePrompt(config())).hasMessageContaining("版本");
        version.setSkillId("instruction");version.setVersionNo(3);
        assertThatThrownBy(()->validator.resolvePrompt(config())).hasMessageContaining("版本");
        version.setVersionNo(2);version.setInstructions("x".repeat(8001));
        assertThatThrownBy(()->validator.resolvePrompt(config())).hasMessageContaining("版本");
        when(versions.selectOne(any())).thenReturn(null);
        assertThatThrownBy(()->validator.resolvePrompt(config())).hasMessageContaining("版本");
    }
    @Test void rejectsMismatchedBodyAndHashAndDisabledCurrentAsset(){
        seed();
        assertThatThrownBy(()->validator.resolvePrompt(config().withPrompt("other",config().promptAsset())))
                .hasMessageContaining("不一致");
        assertThatThrownBy(()->validator.resolvePrompt(config().withPrompt("",new AgentConfiguration.PromptReference("instruction",2,"bad"))))
                .hasMessageContaining("不一致");
        var head=new IqcSkill();head.setStatus("DISABLED");when(skills.selectById("instruction")).thenReturn(head);
        assertThatThrownBy(()->validator.resolvePrompt(config())).hasMessageContaining("停用");
    }
    @Test void strictJsonVersionAndLegacyOmission() throws Exception {
        for(String value:List.of("\"2\"","2.0","0","-1","2147483648","true")){
            assertThatThrownBy(()->json.readValue("{\"skillId\":\"instruction\",\"versionNo\":"+value+"}",AgentConfiguration.PromptReference.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
        var legacy=new AgentConfiguration("3.0",null,"inline",null,null,null,null,"model",List.of(),List.of(),List.of(),null,null);
        assertThat(json.writeValueAsString(legacy)).doesNotContain("promptAsset");
        assertThat(validator.resolvePrompt(legacy)).isSameAs(legacy);
        verifyNoInteractions(skills,versions);
    }
}
