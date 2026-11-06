package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;
import java.util.HashSet;
import java.util.List;

/** Task-owned configuration: inspection and labeling each own an ordered engine list. */
public record TaskProcessingConfiguration(String schemaVersion, Processing inspection, Processing labeling) {
    public static final String SCHEMA = "iqc-task-processing-v1";
    private static final int MAX_ENGINES = 32;

    public TaskProcessingConfiguration {
        if (!SCHEMA.equals(schemaVersion)) throw IqcException.invalidArgument("不支持的任务处理配置版本");
        if (inspection == null && labeling == null)
            throw IqcException.invalidArgument("请至少选择质检或打标");
    }

    /** Engine names describe executable capabilities; small models are reserved for a later release. */
    public enum EngineType { REGEX, DLS, LLM }

    /** Array position is execution order; an absent processing branch means it was not selected. */
    public record Processing(List<Engine> engines) {
        public Processing {
            if (engines == null || engines.isEmpty() || engines.size() > MAX_ENGINES)
                throw IqcException.invalidArgument("每个处理项目需配置 1 到 32 个引擎");
            var ids = new HashSet<String>();
            for (var engine : engines) {
                if (engine == null || !ids.add(engine.id()))
                    throw IqcException.invalidArgument("同一处理项目的引擎标识不能为空或重复");
            }
            engines = List.copyOf(engines);
        }
    }

    /** Parameter trees are copied on both boundaries so callers cannot mutate a frozen task configuration. */
    public record Engine(String id, EngineType type, JsonNode parameters, String name) {
        public Engine {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || type == null)
                throw IqcException.invalidArgument("引擎标识或类型无效");
            name = name == null ? id : name.trim();
            if (name.isBlank() || name.length() > 80)
                throw IqcException.invalidArgument("引擎名称需为 1 到 80 个字符");
            if (parameters == null || !parameters.isObject())
                throw IqcException.invalidArgument("引擎参数必须为对象");
            parameters = parameters.deepCopy();
        }
        /** Older clients omitted names; preserve their stable instance identity as the display fallback. */
        public Engine(String id, EngineType type, JsonNode parameters) { this(id, type, parameters, null); }
        @Override public JsonNode parameters() { return parameters.deepCopy(); }
    }
}
