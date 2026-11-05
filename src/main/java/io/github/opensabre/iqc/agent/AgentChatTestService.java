package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/** Stateless capability testing: never creates tasks or runs business rules. */
@Service
public class AgentChatTestService {
    private final ObjectMapper json;
    private final AgentAssetReferenceValidator assets;
    private final SnapshotChatModelRouter models;
    private final SnapshotMcpToolProvider tools;
    private final io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper versions;
    public AgentChatTestService(ObjectMapper json, AgentAssetReferenceValidator assets,
                                SnapshotChatModelRouter models, SnapshotMcpToolProvider tools, io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper versions) {
        this.json=json; this.assets=assets; this.models=models; this.tools=tools; this.versions=versions;
    }
    public Result chat(Request request) {
        if (request == null || request.configJson() == null || request.configJson().length() > 100000
                || request.messages() == null || request.messages().isEmpty() || request.messages().size() > 40)
            throw IqcException.invalidArgument("请提供智能体配置和 1–40 条聊天消息");
        if ((request.agentId() == null) != (request.versionNo() == null))
            throw IqcException.invalidArgument("智能体与版本必须同时指定");
        AgentConfiguration config;
        try { config=json.readValue(request.configJson(), AgentConfiguration.class); }
        catch (Exception error) { throw IqcException.invalidArgument("智能体配置格式错误"); }
        // Never trust assetSnapshots supplied by the browser, including credential references.
        if (request.agentId() != null && request.versionNo() != null) {
            var version=versions.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<io.github.opensabre.iqc.agent.model.QualityAgentVersion>lambdaQuery()
                    .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getAgentId, request.agentId())
                    .eq(io.github.opensabre.iqc.agent.model.QualityAgentVersion::getVersionNo, request.versionNo()));
            if (version == null) throw IqcException.notFound("智能体版本不存在");
            try { config=json.readValue(version.getConfigJson(), AgentConfiguration.class); }
            catch (Exception error) { throw IqcException.invalidArgument("保存版本的配置格式错误"); }
            config=chatCapabilities(config);
            config.validated();
            if (config.assetSnapshots() == null || !("PUBLISHED".equals(version.getStatus()) || "PENDING_APPROVAL".equals(version.getStatus())))
                config=config.withSnapshots(assets.snapshot(config));
        } else {
            if (!AgentConfiguration.CAPABILITY_SCHEMA.equals(config.schemaVersion()))
                throw IqcException.invalidArgument("请先配置纯大模型能力后测试聊天");
            config.validated();
            config=config.withSnapshots(assets.snapshot(config));
        }
        StringBuilder system=new StringBuilder(config.systemPrompt());
        (config.assetSnapshots().skills() == null ? List.<AgentConfiguration.SkillSnapshot>of() : config.assetSnapshots().skills()).forEach(skill -> system.append("\n\nSkill: ").append(skill.name())
                .append("\n").append(skill.instructions()).append("\n输入约定: ").append(skill.inputSchemaJson())
                .append("\n输出约定: ").append(skill.outputSchemaJson()));
        List<Message> messages=new ArrayList<>(); messages.add(new SystemMessage(system.toString()));
        int length=0;
        for (int index=0; index<request.messages().size(); index++) {
            Turn turn=request.messages().get(index);
            String expected=index % 2 == 0 ? "user" : "assistant";
            if (turn == null || !expected.equals(turn.role()) || turn.content() == null || turn.content().isBlank())
                throw IqcException.invalidArgument("聊天消息必须由用户开始，按用户与助手交替排列");
            length+=turn.content().length();
            if (length > 32000) throw IqcException.invalidArgument("聊天上下文超过 32000 字符，请重新开始会话");
            messages.add("user".equals(turn.role()) ? new UserMessage(turn.content()) : new AssistantMessage(turn.content()));
        }
        if (!"user".equals(request.messages().getLast().role())) throw IqcException.invalidArgument("最后一条消息必须来自用户");
        var snapshot=json.createObjectNode().set("configJson", json.valueToTree(config));
        long start=System.nanoTime();
        String phase="MCP 连接或工具发现";
        try (var session=tools.open(snapshot)) {
            phase="模型调用";
            List<String> calledTools=new ArrayList<>();
            var callbacks=java.util.Arrays.stream(session.callbacks()).map(callback -> new org.springframework.ai.tool.ToolCallback() {
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
                public String call(String arguments) { calledTools.add(callback.getToolDefinition().name()); try { return callback.call(arguments); } catch (RuntimeException error) { throw IqcException.invalidState("MCP 工具调用失败，请检查工具服务与授权配置"); } }
            }).toArray(org.springframework.ai.tool.ToolCallback[]::new);
            var response=models.call(new Prompt(messages), snapshot, callbacks);
            if (response == null || response.getResult() == null || response.getResult().getOutput().getText() == null)
                throw IqcException.invalidState("模型未返回回复");
            return new Result(response.getResult().getOutput().getText(), (System.nanoTime()-start)/1000000,
                    response.getMetadata().getModel(), List.copyOf(calledTools));
        } catch (IqcException error) { throw error; }
        catch (RuntimeException error) { throw IqcException.invalidState(phase+"失败，请检查对应服务连接、认证引用及超时配置"); }
    }
    /** Read-only projection of a trusted legacy version: no task policy is executed or persisted. */
    private AgentConfiguration chatCapabilities(AgentConfiguration config) {
        if (AgentConfiguration.CAPABILITY_SCHEMA.equals(config.schemaVersion())) return config;
        if (!"2.0".equals(config.schemaVersion()) || "RULE_ONLY".equalsIgnoreCase(config.mode())
                || config.primaryModelProfileId() == null || config.primaryModelProfileId().isBlank())
            throw IqcException.invalidArgument("此历史版本没有可测试的大模型能力，请配置新的智能体版本");
        boolean extensions="AGENT_LLM".equals(config.mode());
        var frozen=config.assetSnapshots();
        if (frozen != null && !extensions)
            frozen=new AgentConfiguration.AssetSnapshots(frozen.primaryModel(),frozen.fallbackModels(),List.of(),List.of());
        return new AgentConfiguration("3.0",null,config.systemPrompt(),null,List.of(),List.of(),List.of(),
                config.primaryModelProfileId(),config.fallbackModelProfileIds(),
                extensions ? config.mcpServerIds() : List.of(),extensions ? config.skillIds() : List.of(),frozen,null);
    }

    public record Request(String configJson, List<Turn> messages, String agentId, Integer versionNo) {
        public Request(String configJson, List<Turn> messages) { this(configJson,messages,null,null); }
    }
    public record Turn(String role, String content) { }
    public record Result(String content, long latencyMillis, String model, List<String> calledTools) { }
}
