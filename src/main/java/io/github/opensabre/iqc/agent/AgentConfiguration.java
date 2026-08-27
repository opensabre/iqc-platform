package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.governance.IqcException;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned runtime configuration stored with each IQC Agent snapshot. */
public record AgentConfiguration(String schemaVersion, String systemPrompt, String primaryModel,
                                 List<Model> models, List<McpServer> mcpServers, List<Skill> skills,
                                 String primaryModelProfileId, List<String> fallbackModelProfileIds,
                                 List<String> mcpServerIds, List<String> skillIds, AssetSnapshots assetSnapshots) {
    public static final String CURRENT_SCHEMA = "2.0";

    /** Returns a usable baseline for new Agents without exposing provider credentials. */
    public static AgentConfiguration defaults() {
        return new AgentConfiguration("1.0",
                "你是专业的客服质检 Agent。严格依据已发布规则判断，输出可追溯的理由和证据。",
                "default", List.of(new Model("default", "SPRING_AI", "", "", 0.1, true)),
                List.of(), List.of(), null, List.of(), List.of(), List.of(), null);
    }

    /** Validates cross-field references and bounds before a configuration enters version history. */
    public AgentConfiguration validated() {
        if (!("1.0".equals(schemaVersion) || CURRENT_SCHEMA.equals(schemaVersion))) throw IqcException.invalidArgument("不支持的 Agent 配置版本: " + schemaVersion);
        if (systemPrompt == null || systemPrompt.isBlank()) throw IqcException.invalidArgument("默认提示词不能为空");
        if (systemPrompt.length() > 8000) throw IqcException.invalidArgument("默认提示词不能超过 8000 字符");
        if (CURRENT_SCHEMA.equals(schemaVersion)) {
            if (blank(primaryModelProfileId)) throw IqcException.invalidArgument("Agent 必须选择主模型配置");
            ensureUnique(fallbackModelProfileIds, "备用模型"); ensureUnique(mcpServerIds, "MCP"); ensureUnique(skillIds, "Skill");
            if (fallbackModelProfileIds != null && fallbackModelProfileIds.contains(primaryModelProfileId)) throw IqcException.invalidArgument("主模型不能同时作为备用模型");
            return this;
        }
        if (models == null || models.isEmpty()) throw IqcException.invalidArgument("Agent 至少配置一个模型");
        Set<String> modelIds = new HashSet<>();
        for (Model model : models) {
            if (blank(model.id()) || blank(model.provider())) throw IqcException.invalidArgument("模型标识和供应商不能为空");
            if (!modelIds.add(model.id())) throw IqcException.invalidArgument("模型标识不能重复: " + model.id());
            if (model.temperature() != null && (model.temperature() < 0 || model.temperature() > 2))
                throw IqcException.invalidArgument("模型 temperature 必须在 0 到 2 之间");
        }
        if (blank(primaryModel) || !modelIds.contains(primaryModel)) throw IqcException.invalidArgument("主模型必须引用模型列表中的标识");
        Set<String> mcpNames = new HashSet<>();
        for (McpServer server : mcpServers == null ? List.<McpServer>of() : mcpServers) {
            if (blank(server.name()) || blank(server.transport()) || blank(server.endpoint()))
                throw IqcException.invalidArgument("MCP 名称、传输类型和地址不能为空");
            if (!mcpNames.add(server.name())) throw IqcException.invalidArgument("MCP 名称不能重复: " + server.name());
            validateHttpEndpoint(server.endpoint(), "MCP 地址");
        }
        Set<String> skillNames = new HashSet<>();
        for (Skill skill : skills == null ? List.<Skill>of() : skills) {
            if (blank(skill.name())) throw IqcException.invalidArgument("Skill 名称不能为空");
            if (!skillNames.add(skill.name())) throw IqcException.invalidArgument("Skill 名称不能重复: " + skill.name());
            if (skill.instructions() != null && skill.instructions().length() > 8000)
                throw IqcException.invalidArgument("单个 Skill 指令不能超过 8000 字符");
        }
        return this;
    }

    private static void validateHttpEndpoint(String value, String label) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw IqcException.invalidArgument(label + "必须是有效的 HTTP(S) URL");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void ensureUnique(List<String> values, String label) {
        if (values == null) return;
        Set<String> unique = new HashSet<>();
        for (String value : values) if (blank(value) || !unique.add(value)) throw IqcException.invalidArgument(label + " 引用不能为空或重复");
    }

    public record Model(String id, String provider, String model, String endpoint, Double temperature, boolean enabled) { }
    public record McpServer(String name, String transport, String endpoint, boolean enabled) { }
    public record Skill(String name, String description, String instructions, boolean enabled) { }
    public record AssetSnapshots(ModelSnapshot primaryModel, List<ModelSnapshot> fallbackModels,
                                 List<McpSnapshot> mcpServers, List<SkillSnapshot> skills) { }
    public record ModelSnapshot(String id, String code, String name, String provider, String modelName,
                                String endpoint, String secretRef, Double temperature, Integer timeoutSeconds,
                                Integer maxRetries, Integer versionNo) { }
    public record McpSnapshot(String id, String code, String name, String transport, String endpoint,
                              String authType, String secretRef, Integer timeoutSeconds,
                              String allowedToolsJson, Integer versionNo) { }
    public record SkillSnapshot(String id, String code, String name, String description, String instructions,
                                String inputSchemaJson, String outputSchemaJson, Integer versionNo) { }

    /** Returns a copy with immutable asset details captured for approval and execution. */
    public AgentConfiguration withSnapshots(AssetSnapshots snapshots) {
        return new AgentConfiguration(schemaVersion, systemPrompt, primaryModel, models, mcpServers, skills,
                primaryModelProfileId, fallbackModelProfileIds, mcpServerIds, skillIds, snapshots);
    }
}
