package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;

import java.util.Locale;

/** Task-owned route snapshot; legacy tasks without this object still use their original Agent mode. */
public record TaskExecutionStrategy(String schemaVersion, Mode mode) {
    public enum Mode { RULE_ONLY, RULE_THEN_LLM, LLM_THEN_RULE, AGENT_LLM, INDEPENDENT }

    public TaskExecutionStrategy {
        if (!"1.0".equals(schemaVersion) || mode == null) throw IqcException.invalidArgument("无效的任务执行策略");
    }

    /** Null means the caller explicitly uses the old task API contract. */
    public static Mode parseRequested(String mode) {
        if (mode == null) return null;
        try { return Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw IqcException.invalidArgument("不支持的任务执行策略: " + mode); }
    }

    /** Fails closed on corrupt or unknown explicit strategy snapshots rather than falling back silently. */
    public static Mode fromSnapshot(JsonNode ruleSnapshot) {
        if (ruleSnapshot == null || !ruleSnapshot.has("executionStrategy")) return null;
        JsonNode strategy = ruleSnapshot.path("executionStrategy");
        if (!"1.0".equals(strategy.path("schemaVersion").asText())) throw IqcException.invalidState("不支持的任务执行策略快照版本");
        Mode mode = parseRequested(strategy.path("mode").asText(""));
        if (mode == null) throw IqcException.invalidState("任务执行策略缺少模式");
        return mode;
    }
}
