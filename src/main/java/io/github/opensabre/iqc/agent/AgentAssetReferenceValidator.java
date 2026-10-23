package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.skill.dao.IqcSkillMapper;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.List;
import static io.github.opensabre.iqc.agent.AgentConfiguration.*;

/** Validates that new Agent configurations only reference enabled managed assets. */
@Component
public class AgentAssetReferenceValidator {
    private final IqcModelProfileMapper models; private final IqcMcpServerMapper mcps; private final IqcSkillMapper skills;
    private final io.github.opensabre.iqc.skill.dao.IqcSkillVersionMapper skillVersions;
    @org.springframework.beans.factory.annotation.Autowired
    public AgentAssetReferenceValidator(IqcModelProfileMapper models,IqcMcpServerMapper mcps,IqcSkillMapper skills,
            io.github.opensabre.iqc.skill.dao.IqcSkillVersionMapper skillVersions){
        this.models=models;this.mcps=mcps;this.skills=skills;this.skillVersions=skillVersions;
    }
    public AgentAssetReferenceValidator(IqcModelProfileMapper models,IqcMcpServerMapper mcps,IqcSkillMapper skills){this(models,mcps,skills,null);}
    /** Resolve an exact independent instruction version, never the mutable current instructions. */
    public AgentConfiguration resolvePrompt(AgentConfiguration config){
        var reference=config.promptAsset();if(reference==null)return config;
        if(!CAPABILITY_SCHEMA.equals(config.schemaVersion())||reference.skillId()==null||!reference.skillId().matches("[A-Za-z0-9:_-]{1,64}")||reference.versionNo()<1)
            throw IqcException.invalidArgument("Prompt来源须为精确指令资产版本");
        if(skillVersions==null)throw IqcException.invalidState("Prompt版本解析依赖不可用");
        requireEnabledSkill(reference.skillId());
        var version=skillVersions.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<io.github.opensabre.iqc.skill.model.IqcSkillVersion>lambdaQuery()
                .eq(io.github.opensabre.iqc.skill.model.IqcSkillVersion::getSkillId,reference.skillId())
                .eq(io.github.opensabre.iqc.skill.model.IqcSkillVersion::getVersionNo,reference.versionNo()));
        if(version==null||!reference.skillId().equals(version.getSkillId())||!java.util.Objects.equals(reference.versionNo(),version.getVersionNo())
                ||!"ENABLED".equals(version.getStatus())||version.getInstructions()==null||version.getInstructions().isBlank()||version.getInstructions().length()>8000)
            throw IqcException.invalidState("Prompt指令版本不存在、已停用或正文超限");
        var text=version.getInstructions();var hash=promptHash(reference,text);
        if((config.systemPrompt()!=null&&!config.systemPrompt().isBlank()&&!text.equals(config.systemPrompt()))
                ||(reference.contentHash()!=null&&!hash.equals(reference.contentHash())))throw IqcException.invalidArgument("Prompt正文或摘要与精确版本不一致");
        return config.withPrompt(text,new PromptReference(reference.skillId(),reference.versionNo(),hash));
    }
    public void validatePrompt(AgentConfiguration config){
        if(config.promptAsset()!=null && (config.promptAsset().contentHash()==null||!resolvePrompt(config).equals(config)))
            throw IqcException.invalidState("Prompt版本尚未解析冻结");
    }
    public void validate(AgentConfiguration config) {
        validatePrompt(config);
        if (!AgentConfiguration.usesManagedAssets(config.schemaVersion())) return;
        if ("RULE_ONLY".equals(config.mode())) return;
        requireEnabledModel(config.primaryModelProfileId());
        distinct(config.fallbackModelProfileIds()).forEach(this::requireEnabledModel);
        if (!AgentConfiguration.CAPABILITY_SCHEMA.equals(config.schemaVersion()) && !"AGENT_LLM".equals(config.mode())) return;
        distinct(config.mcpServerIds()).forEach(this::requireEnabledMcp);
        distinct(config.skillIds()).forEach(this::requireEnabledSkill);
    }
    /** Checks current availability without replacing the caller's frozen Skill/MCP version bodies. */
    public void validateDetectionCapabilities(String skillId,String mcpId) {
        requireEnabledSkill(skillId);requireEnabledMcp(mcpId);
    }
    /** Availability only: retain the published model and MCP snapshot bodies. */
    public void validateInferenceCapabilities(String modelId,String mcpId) {
        requireEnabledModel(modelId);requireEnabledMcp(mcpId);
    }
    private void requireEnabledMcp(String id) {var item=mcps.selectById(id);if(item==null||!"ENABLED".equals(item.getStatus()))throw IqcException.invalidState("MCP 不存在或已停用: "+id);}
    private void requireEnabledSkill(String id) {var item=skills.selectById(id);if(item==null||!"ENABLED".equals(item.getStatus()))throw IqcException.invalidState("Skill 不存在或已停用: "+id);}
    /** Resolves enabled references into a secret-free immutable Agent asset snapshot. */
    public AssetSnapshots snapshot(AgentConfiguration config) {
        validate(config);
        if (!AgentConfiguration.usesManagedAssets(config.schemaVersion())) return null;
        boolean ruleOnly="RULE_ONLY".equals(config.mode()), agent=AgentConfiguration.CAPABILITY_SCHEMA.equals(config.schemaVersion()) || "AGENT_LLM".equals(config.mode());
        return new AssetSnapshots(ruleOnly ? null : model(config.primaryModelProfileId()), ruleOnly ? List.of() : distinct(config.fallbackModelProfileIds()).stream().map(this::model).toList(),
                !agent ? List.of() : distinct(config.mcpServerIds()).stream().map(id -> { var x=mcps.selectById(id); return new McpSnapshot(x.getId(),x.getCode(),x.getName(),x.getTransport(),x.getEndpoint(),x.getAuthType(),x.getSecretRef(),x.getTimeoutSeconds(),x.getAllowedToolsJson(),x.getVersionNo()); }).toList(),
                !agent ? List.of() : distinct(config.skillIds()).stream().map(id -> { var x=skills.selectById(id); return new SkillSnapshot(x.getId(),x.getCode(),x.getName(),x.getDescription(),x.getInstructions(),x.getInputSchemaJson(),x.getOutputSchemaJson(),x.getVersionNo()); }).toList());
    }
    private ModelSnapshot model(String id) { var x=models.selectById(id); return new ModelSnapshot(x.getId(),x.getCode(),x.getName(),x.getProvider(),x.getModelName(),x.getEndpoint(),x.getSecretRef(),x.getTemperature(),x.getTimeoutSeconds(),x.getMaxRetries(),x.getVersionNo(),io.github.opensabre.iqc.modelprofile.ModelPricing.canonical(x.getPricingJson()),x.getMaxOutputTokens(),x.getOutputTokenParameter(),x.getContextWindowTokens(),x.getDeploymentRef()); }
    private void requireEnabledModel(String id) { var item=models.selectById(id); if(item==null||!"ENABLED".equals(item.getStatus())) throw IqcException.invalidState("模型配置不存在或已停用: "+id); }
    private static List<String> distinct(List<String> ids) { return ids==null ? List.of() : List.copyOf(new LinkedHashSet<>(ids)); }
}
