package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import java.util.ArrayList;
import java.util.List;

/** Compares additional semantic projections without altering either frozen release. */
public final class BenchmarkVariableComparator {
    private BenchmarkVariableComparator() { }
    public enum Dimension { PROMPT, MODEL, SKILL, FUSION, PIPELINE }
    public record Selection(Dimension dimension, String nodeId, Integer slot) { }
    public record Comparison(Selection selection, List<String> allowedPaths, String invariantHash,
            String baselineVariableHash, String candidateVariableHash, String baselineReleaseProjectionHash,
            String candidateReleaseProjectionHash, String baselineEffectivePromptHash, String candidateEffectivePromptHash) {
        public Comparison { allowedPaths = List.copyOf(allowedPaths); }
    }
    private record Projection(ObjectNode invariant, JsonNode variable, String prompt, List<String> paths) { }

    public static Comparison compare(PipelineRelease baseline, PipelineRelease candidate, Selection selection, ObjectMapper mapper) {
        if (baseline == null || candidate == null || selection == null || selection.dimension() == null || mapper == null)
            throw invalid("Benchmark 两侧配置和变量声明不能为空");
        var left = project(baseline, selection, mapper);
        var right = project(candidate, selection, mapper);
        if (!left.invariant().equals(right.invariant()) || !left.paths().equals(right.paths()))
            throw invalid("Benchmark 发现声明变量之外的配置变化");
        String leftVariable = hash(left.variable(), mapper), rightVariable = hash(right.variable(), mapper);
        if (leftVariable.equals(rightVariable)) throw invalid("Benchmark 变量没有实际语义变化");
        String leftPrompt = left.prompt() == null ? null : hash(mapper.getNodeFactory().textNode(left.prompt()), mapper);
        String rightPrompt = right.prompt() == null ? null : hash(mapper.getNodeFactory().textNode(right.prompt()), mapper);
        if ((selection.dimension() == Dimension.PROMPT || selection.dimension() == Dimension.SKILL)
                && leftPrompt.equals(rightPrompt)) throw invalid("Benchmark 变量未改变实际有效提示");
        return new Comparison(selection, left.paths(), hash(left.invariant(), mapper), leftVariable, rightVariable,
                hash(releaseProjection(baseline,mapper), mapper), hash(releaseProjection(candidate,mapper), mapper), leftPrompt, rightPrompt);
    }

    private static Projection project(PipelineRelease release, Selection selection, ObjectMapper mapper) {
        ObjectNode root = releaseProjection(release,mapper);
        ArrayNode nodes = (ArrayNode) root.path("pipeline").path("nodes");
        // All configurations remain schema 3 and tool-free, including a composite PIPELINE variable.
        for (JsonNode node : nodes) if ("LLM".equals(node.path("type").asText())) configuration(node, mapper);
        Dimension dimension = selection.dimension();
        if (dimension == Dimension.FUSION || dimension == Dimension.PIPELINE) {
            if (selection.nodeId() != null || selection.slot() != null) throw invalid("该变量维度不能指定节点或槽位");
            String field = dimension == Dimension.FUSION ? "fusion" : "pipeline";
            JsonNode variable = root.get(field).deepCopy(); root.put(field, "__BENCHMARK_VARIABLE__");
            if(dimension==Dimension.FUSION) ((ObjectNode)variable).remove(List.of("id","version"));
            if(dimension==Dimension.PIPELINE) {
                // A node display-name edit alone does not change detection or orchestration.
                for(JsonNode child:variable.path("nodes")) ((ObjectNode)child).remove("name");
            }
            return new Projection(root, variable, null, List.of("/" + field));
        }
        if (selection.nodeId() == null || selection.nodeId().isBlank()) throw invalid("必须指定单个 LLM 节点");
        int index = -1;
        for (int i = 0; i < nodes.size(); i++) if (selection.nodeId().equals(nodes.get(i).path("nodeId").asText())) {
            if (index >= 0) throw invalid("节点标识重复"); index = i;
        }
        if (index < 0 || !"LLM".equals(nodes.get(index).path("type").asText())) throw invalid("变量节点必须存在且为 LLM");
        ObjectNode node = (ObjectNode) nodes.get(index), config = (ObjectNode) node.get("config");
        ObjectNode agent = (ObjectNode) config.get("agentSnapshot"), agentConfig = configuration(node, mapper);
        String prompt = effectivePrompt(agentConfig);
        String prefix = "/pipeline/nodes/" + index + "/config/agentSnapshot/configJson";
        List<String> paths = new ArrayList<>();
        ObjectNode variable = mapper.createObjectNode();
        switch (dimension) {
            case PROMPT -> {
                if (selection.slot() != null) throw invalid("PROMPT 不接受槽位");
                variable.set("systemPrompt", agentConfig.get("systemPrompt"));
                agentConfig.put("systemPrompt", "__BENCHMARK_VARIABLE__"); paths.add(prefix + "/systemPrompt");
            }
            case MODEL -> {
                if (selection.slot() != null && selection.slot() != 0) throw invalid("当前 MODEL 实验仅支持主模型");
                if (agentConfig.path("fallbackModelProfileIds").size() > 0 || agentConfig.path("assetSnapshots").path("fallbackModels").size() > 0)
                    throw invalid("缺少实际回退模型轨迹，MODEL 实验不能配置备用模型");
                ObjectNode assets = (ObjectNode) agentConfig.get("assetSnapshots");
                ObjectNode model = (ObjectNode) assets.path("primaryModel").deepCopy();
                if (!agentConfig.path("primaryModelProfileId").asText().equals(model.path("id").asText())) throw invalid("主模型引用与冻结快照不一致");
                // Version/display changes alone do not change model routing or call configuration.
                model.remove(List.of("versionNo", "name", "code")); variable.set("primaryModel", model);
                agentConfig.put("primaryModelProfileId", "__BENCHMARK_VARIABLE__"); assets.put("primaryModel", "__BENCHMARK_VARIABLE__");
                paths.add(prefix + "/primaryModelProfileId"); paths.add(prefix + "/assetSnapshots/primaryModel");
            }
            case SKILL -> {
                Integer slot = selection.slot(); JsonNode skills = agentConfig.path("assetSnapshots").path("skills"), ids = agentConfig.path("skillIds");
                if (slot == null || slot < 0 || !skills.isArray() || !ids.isArray() || skills.size() != ids.size() || slot >= skills.size())
                    throw invalid("SKILL 必须指定已冻结的单个有效槽位");
                if (!ids.get(slot).asText().equals(skills.get(slot).path("id").asText())) throw invalid("Skill 引用与冻结快照不一致");
                ObjectNode skill = (ObjectNode) skills.get(slot).deepCopy(); skill.remove("versionNo"); variable.set("skill", skill);
                ((ArrayNode) ids).set(slot, mapper.getNodeFactory().textNode("__BENCHMARK_VARIABLE__"));
                ((ArrayNode) skills).set(slot, mapper.getNodeFactory().textNode("__BENCHMARK_VARIABLE__"));
                paths.add(prefix + "/skillIds/" + slot); paths.add(prefix + "/assetSnapshots/skills/" + slot);
            }
            default -> throw invalid("不支持的变量维度");
        }
        // Only this Agent's release metadata may move as the selected capability changes.
        agent.remove(List.of("versionNo", "name", "code", "description", "createdBy", "createdTime", "updatedBy", "updatedTime"));
        ((ObjectNode) config.path("agentReference")).remove("versionNo");
        agent.set("configJson", agentConfig);
        return new Projection(root, variable, prompt, paths);
    }

    private static ObjectNode configuration(JsonNode node, ObjectMapper mapper) {
        JsonNode raw = node.path("config").path("agentSnapshot").path("configJson");
        JsonNode value;
        try {
            if (raw.isTextual()) {
                if (raw.asText().length() > 1024 * 1024) throw invalid("智能体配置超出比较上限");
                value = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw.asText());
            } else value = raw.deepCopy();
        } catch (Exception ex) { throw invalid("智能体冻结配置不是有效 JSON"); }
        if (!(value instanceof ObjectNode object) || !"3.0".equals(value.path("schemaVersion").asText())
                || value.path("mcpServerIds").size() > 0 || value.path("mcpServers").size() > 0 || value.path("assetSnapshots").path("mcpServers").size() > 0)
            throw invalid("Benchmark 仅支持 schema 3.0 且无 MCP 的冻结智能体");
        if (!value.path("assetSnapshots").isObject() || !value.path("assetSnapshots").path("primaryModel").isObject()
                || !value.path("systemPrompt").isTextual() || value.path("systemPrompt").asText().isBlank()) throw invalid("智能体冻结模型或提示缺失");
        return object;
    }
    /** Mirrors the provider's schema 3 concatenation, rejecting rather than hiding truncation. */
    private static String effectivePrompt(ObjectNode config) {
        StringBuilder prompt = new StringBuilder(config.path("systemPrompt").asText().trim());
        JsonNode skills = config.path("assetSnapshots").path("skills");
        if (skills.isArray()) for (JsonNode skill : skills) {
            String instructions = skill.path("instructions").asText("");
            if (!instructions.isBlank()) prompt.append("\nSkill[").append(skill.path("name").asText("unnamed")).append("]:\n").append(instructions);
        }
        if (prompt.length() > 8000) throw invalid("Benchmark 节点提示会发生 8000 字符截断");
        return prompt.toString();
    }
    private static String hash(JsonNode value, ObjectMapper mapper) {
        try { return PipelineAssetService.hash(mapper.writeValueAsString(value)); }
        catch (Exception ex) { throw invalid("Benchmark 配置摘要失败"); }
    }
    private static ObjectNode releaseProjection(PipelineRelease release,ObjectMapper mapper) {
        ObjectNode root=mapper.valueToTree(release);
        // This domain field is a Set; other arrays retain their meaningful order.
        JsonNode calibrated=root.path("fusion").path("calibratedNodeIds");
        if(calibrated.isArray()) {
            var ids=new ArrayList<String>(); calibrated.forEach(id->ids.add(id.asText())); ids.sort(String::compareTo);
            var sorted=mapper.createArrayNode(); ids.forEach(sorted::add);
            ((ObjectNode)root.get("fusion")).set("calibratedNodeIds",sorted);
        }
        return root;
    }
    private static RuntimeException invalid(String message) { return IqcException.invalidArgument(message); }
}
