package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rule.RuleMatcher;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.task.processing.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Resolves task-owned engine references once; execution consumes only the frozen server-owned plan. */
@Service
public class TaskEnginePlanService {
    private final QualityRuleMapper rules;
    private final QualityAgentMapper agents;
    private final ObjectMapper mapper;
    private final RegexEngineAdapter regex;
    private final DlsEngineAdapter dls;
    private final LlmEngineAdapter llm;

    public TaskEnginePlanService(QualityRuleMapper rules, QualityAgentMapper agents, ObjectMapper mapper,
                                RegexEngineAdapter regex, DlsEngineAdapter dls, LlmEngineAdapter llm) {
        this.rules = rules; this.agents = agents; this.mapper = mapper;
        this.regex = regex; this.dls = dls; this.llm = llm;
    }

    /** Only published references are accepted; expressions, model snapshots and credentials cannot be overridden. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public FrozenPlan freeze(TaskProcessingConfiguration request) {
        java.util.Objects.requireNonNull(request);
        var frozen = new ArrayList<FrozenEngine>();
        freezeOperation(request.inspection(), Operation.INSPECTION, frozen);
        freezeOperation(request.labeling(), Operation.LABELING, frozen);
        return new FrozenPlan(FrozenPlan.SCHEMA, frozen);
    }

    private void freezeOperation(TaskProcessingConfiguration.Processing operation, Operation kind,
                                 List<FrozenEngine> frozen) {
        if (operation == null) return;
        int position = 0;
        for (var engine : operation.engines()) {
            JsonNode parameters = engine.parameters();
            Set<String> allowed = engine.type() == TaskProcessingConfiguration.EngineType.LLM
                    ? Set.of("targetId", "ruleId", "agentId", "prompt", "temperature", "timeoutSeconds") : Set.of("targetId", "ruleId");
            parameters.fieldNames().forEachRemaining(key -> {
                if (!allowed.contains(key)) throw IqcException.invalidArgument("不支持的引擎参数: " + key);
            });
            String target = text(parameters, "targetId");
            String ruleId = text(parameters, "ruleId");
            var rule = rules.selectById(ruleId);
            if (rule == null || !"PUBLISHED".equals(rule.getStatus()))
                throw IqcException.invalidArgument("只能选择已发布规则: " + ruleId);
            if (!engine.type().name().equals(rule.getRuleType()))
                throw IqcException.invalidArgument("引擎类型与规则类型不一致: " + ruleId);
            RuleMatcher.validate(rule.getRuleType(), rule.getExpression(), mapper);
            var configuration = mapper.createObjectNode().put("targetId", target).put("ruleId", ruleId)
                    .put("engineName", engine.name());
            configuration.set("rule", mapper.valueToTree(rule));
            if (engine.type() == TaskProcessingConfiguration.EngineType.LLM) {
                String agentId = text(parameters, "agentId");
                var agent = agents.selectById(agentId);
                if (agent == null || !"PUBLISHED".equals(agent.getStatus()))
                    throw IqcException.invalidArgument("只能选择已发布大模型配置: " + agentId);
                try {
                    JsonNode agentConfig = mapper.readTree(agent.getConfigJson());
                    if (agentConfig == null || !agentConfig.isObject()
                            || "RULE_ONLY".equalsIgnoreCase(agentConfig.path("mode").asText()))
                        throw IqcException.invalidArgument("所选配置不具备大模型能力");
                } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
                    throw IqcException.invalidArgument("大模型配置无效");
                }
                var agentSnapshot = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(agent);
                var overrides = mapper.createObjectNode();
                if (parameters.has("prompt")) {
                    JsonNode prompt = parameters.get("prompt");
                    if (!prompt.isTextual() || prompt.asText().isBlank() || prompt.asText().trim().length() > 8000)
                        throw IqcException.invalidArgument("任务提示词需为 1 到 8000 个字符");
                    overrides.put("prompt", prompt.asText().trim());
                }
                if (parameters.has("temperature")) {
                    JsonNode temperature = parameters.get("temperature");
                    if (!temperature.isNumber() || !Double.isFinite(temperature.asDouble())
                            || temperature.asDouble() < 0 || temperature.asDouble() > 1)
                        throw IqcException.invalidArgument("任务温度需在 0 到 1 之间");
                    overrides.set("temperature", temperature);
                }
                if (parameters.has("timeoutSeconds")) {
                    JsonNode timeout = parameters.get("timeoutSeconds");
                    if (!timeout.isIntegralNumber() || !timeout.canConvertToInt() || timeout.asInt() < 1 || timeout.asInt() > 120)
                        throw IqcException.invalidArgument("任务超时需为 1 到 120 秒的整数");
                    overrides.set("timeoutSeconds", timeout);
                }
                if (!overrides.isEmpty()) agentSnapshot.set("taskOverrides", overrides);
                configuration.put("agentId", agentId).set("agentSnapshot", agentSnapshot);
            } else {
                configuration.put("expression", rule.getExpression());
                if (engine.type() == TaskProcessingConfiguration.EngineType.REGEX
                        && rule.getTargetRole() != null && !"ALL".equalsIgnoreCase(rule.getTargetRole())
                        && !rule.getTargetRole().isBlank()) configuration.put("speakerRole", rule.getTargetRole());
            }
            frozen.add(new FrozenEngine(kind, engine.id(), EngineType.valueOf(engine.type().name()), ++position, configuration));
        }
    }

    /** Rebuilds executable steps without reading mutable rules or Agents; each operation owns its order. */
    public List<MultiEngineExecutor.Step> steps(FrozenPlan plan, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) throw IqcException.invalidArgument("执行会话标识不能为空");
        return plan.engines().stream().map(engine -> {
            var config = (com.fasterxml.jackson.databind.node.ObjectNode) engine.configuration();
            if (engine.type() == EngineType.LLM) config.put("conversationId", conversationId);
            MultiEngineExecutor.Adapter adapter = switch (engine.type()) {
                case REGEX -> regex; case DLS -> dls; case LLM -> llm;
            };
            return new MultiEngineExecutor.Step(engine.operation(), engine.instanceId(), engine.type(), engine.sequence(), config, adapter);
        }).toList();
    }

    /** Serialized inside the existing task snapshot, never accepted as the client's reference configuration. */
    public record FrozenPlan(String schemaVersion, List<FrozenEngine> engines) {
        public static final String SCHEMA = "iqc-task-engine-plan-v1";
        public FrozenPlan {
            if (!SCHEMA.equals(schemaVersion) || engines == null || engines.isEmpty() || engines.size() > 64)
                throw IqcException.invalidArgument("任务引擎计划无效");
            engines = List.copyOf(engines);
            var identities = new HashSet<String>();
            var positions = new HashSet<String>();
            for (var engine : engines) {
                if (!identities.add(engine.operation() + ":" + engine.instanceId())
                        || !positions.add(engine.operation() + ":" + engine.sequence()))
                    throw IqcException.invalidArgument("任务引擎标识或顺序重复");
            }
        }
    }

    /** Frozen engine configuration is copied on construction and access. */
    public record FrozenEngine(Operation operation, String instanceId, EngineType type, int sequence, JsonNode configuration) {
        public FrozenEngine {
            if (operation == null || instanceId == null || instanceId.isBlank() || type == null || sequence < 1
                    || configuration == null || !configuration.isObject()) throw IqcException.invalidArgument("任务引擎快照无效");
            configuration = configuration.deepCopy();
        }
        @Override public JsonNode configuration() { return configuration.deepCopy(); }
    }
    private static String text(JsonNode value, String key) {
        JsonNode field = value.get(key);
        if (field == null || !field.isTextual() || field.asText().isBlank() || field.asText().length() > 64)
            throw IqcException.invalidArgument("引擎参数缺少或无效: " + key);
        return field.asText();
    }
}
