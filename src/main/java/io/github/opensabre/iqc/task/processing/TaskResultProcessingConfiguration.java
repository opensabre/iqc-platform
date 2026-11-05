package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelFactEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.util.HashSet;
import java.util.List;

/** Explicit result-processor policy; detector hit values have no implicit scoring or labeling meaning. */
public record TaskResultProcessingConfiguration(String schemaVersion, Inspection inspection, List<Label> labeling) {
    public static final String SCHEMA = "iqc-task-result-processing-v1";
    public TaskResultProcessingConfiguration {
        if (!SCHEMA.equals(schemaVersion)) throw IqcException.invalidArgument("不支持的结果处理配置版本");
        labeling = labeling == null ? List.of() : List.copyOf(labeling);
        if (inspection == null && labeling.isEmpty()) throw IqcException.invalidArgument("至少配置一项结果处理");
        if (labeling.size() > 32) throw IqcException.invalidArgument("标签处理数量超限");
        unique(labeling.stream().map(Label::targetId).toList(), "标签目标");
    }
    /** This is an explicit processor rule, never inferred from engine order. */
    public enum Aggregation { REQUIRE_AGREEMENT }
    public record Inspection(InspectionScoring.Policy scoring, Aggregation aggregation, List<Check> checks) {
        public Inspection {
            if (scoring == null || aggregation == null || checks == null || checks.isEmpty() || checks.size() > 32)
                throw IqcException.invalidArgument("请配置评分政策、汇总规则和质检项");
            checks = List.copyOf(checks);
            unique(checks.stream().map(Check::targetId).toList(), "质检项");
            if (!new HashSet<>(scoring.items().stream().map(InspectionScoring.Item::itemCode).toList())
                    .equals(new HashSet<>(checks.stream().map(Check::targetId).toList())))
                throw IqcException.invalidArgument("评分项目与质检目标必须一一对应");
        }
    }
    public record Check(String targetId, List<JudgmentMapping> engines) {
        public Check { required(targetId); engines = mappings(engines); unique(engines.stream().map(JudgmentMapping::engineInstanceId).toList(), "质检引擎"); }
    }
    /** Both true and false must be mapped deliberately; neither means violation by default. */
    public record JudgmentMapping(String engineInstanceId, InspectionScoring.ItemStatus onHit, InspectionScoring.ItemStatus onMiss) {
        public JudgmentMapping {
            required(engineInstanceId);
            if ((onHit != InspectionScoring.ItemStatus.PASS && onHit != InspectionScoring.ItemStatus.FAIL)
                    || (onMiss != InspectionScoring.ItemStatus.PASS && onMiss != InspectionScoring.ItemStatus.FAIL))
                throw IqcException.invalidArgument("命中与未命中须明确映射为通过或不通过");
        }
    }
    /** Value type is checked against the published label definition when the configuration is frozen. */
    public record Label(String targetId, String valueCode, String valueType, Aggregation aggregation, List<ValueMapping> engines) {
        public Label {
            required(targetId); required(valueCode); required(valueType);
            if (aggregation == null) throw IqcException.invalidArgument("请选择标签汇总规则");
            engines = mappings(engines); unique(engines.stream().map(ValueMapping::engineInstanceId).toList(), "标签引擎");
            for (var engine : engines) {
                if (!valid(valueType, engine.onHit()) || !valid(valueType, engine.onMiss()))
                    throw IqcException.invalidArgument("标签映射值与类型不一致");
            }
        }
        private static boolean valid(String type, JsonNode value) {
            // Null is an explicit unknown mapping, not a new label value.
            return value.isNull() || LabelFactEvaluator.validValue(type, value);
        }
    }
    public record ValueMapping(String engineInstanceId, JsonNode onHit, JsonNode onMiss) {
        public ValueMapping {
            required(engineInstanceId);
            if (onHit == null || onMiss == null) throw IqcException.invalidArgument("请明确配置命中与未命中的标签值或未知值");
            onHit = onHit.deepCopy(); onMiss = onMiss.deepCopy();
        }
        @Override public JsonNode onHit() { return onHit.deepCopy(); }
        @Override public JsonNode onMiss() { return onMiss.deepCopy(); }
    }
    private static <T> List<T> mappings(List<T> values) {
        if (values == null || values.isEmpty() || values.size() > 32 || values.stream().anyMatch(java.util.Objects::isNull))
            throw IqcException.invalidArgument("每个结果目标需配置 1 到 32 项引擎映射");
        return List.copyOf(values);
    }
    private static void unique(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) throw IqcException.invalidArgument(name + "重复");
    }
    private static void required(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || !value.equals(value.trim()))
            throw IqcException.invalidArgument("目标、值编码或引擎标识无效");
    }
}
