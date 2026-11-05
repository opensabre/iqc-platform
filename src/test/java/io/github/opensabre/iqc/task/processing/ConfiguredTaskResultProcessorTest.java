package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static io.github.opensabre.iqc.task.processing.TaskResultProcessingConfiguration.*;
import static io.github.opensabre.iqc.scoring.InspectionScoring.ItemStatus.*;
import static org.assertj.core.api.Assertions.*;

class ConfiguredTaskResultProcessorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ConfiguredTaskResultProcessor processor = new ConfiguredTaskResultProcessor(mapper);

    @Test void scoresOncePerTargetAndKeepsEverySourceWithoutMutatingRawResults() throws Exception {
        var source = set(List.of(engine(Operation.INSPECTION, "one", 1, true, false), engine(Operation.INSPECTION, "two", 2, true, false)));
        var before = mapper.writeValueAsString(source);
        var result = processor.process(source, policy(true, false));
        assertThat(result.output().at("/scoring/finalScore").intValue()).isEqualTo(90);
        assertThat(result.output().at("/scoring/scoreStatus").asText()).isEqualTo("FINAL");
        assertThat(result.output().at("/inspectionItems/0/candidates").size()).isEqualTo(2);
        assertThat(result.sourceEngineResultIds()).containsExactly("result-one", "result-two");
        assertThat(mapper.writeValueAsString(source)).isEqualTo(before);
    }
    @Test void disagreementLeavesScorePendingAndPreservesBothValues() {
        var result = processor.process(set(List.of(engine(Operation.INSPECTION, "one", 1, true, false),
                engine(Operation.INSPECTION, "two", 2, false, false))), policy(true, false));
        assertThat(result.output().at("/inspectionItems/0/status").asText()).isEqualTo("CONFLICT");
        assertThat(result.output().at("/scoring/scoreStatus").asText()).isEqualTo("PENDING");
        assertThat(result.output().at("/scoring/finalScore").isNull()).isTrue();
        assertThat(result.output().at("/inspectionItems/0/value").isMissingNode()).isTrue();
    }
    @Test void failedInspectionDoesNotEraseIndependentFalseLabelValue() {
        var result = processor.process(set(List.of(engine(Operation.INSPECTION, "one", 1, true, true),
                engine(Operation.INSPECTION, "two", 2, true, false), engine(Operation.LABELING, "label", 1, false, false))), policy(true, true));
        assertThat(result.output().at("/scoring/scoreStatus").asText()).isEqualTo("PENDING");
        assertThat(result.output().at("/inspectionItems/0/status").asText()).isEqualTo("ERROR");
        assertThat(result.output().at("/inspectionItems/0/candidates/0/failure/code").asText()).isEqualTo("TIMEOUT");
        assertThat(result.output().at("/labels/0/status").asText()).isEqualTo("KNOWN");
        assertThat(result.output().at("/labels/0/value").booleanValue()).isFalse();
        assertThat(result.sourceEngineResultIds()).hasSize(3);
    }
    @Test void missingEngineAndExplicitUnknownAreNeverPassesOrInventedLabels() {
        var onlyOne = processor.process(set(List.of(engine(Operation.INSPECTION, "one", 1, true, false))), policy(true, false));
        assertThat(onlyOne.output().at("/scoring/scoreStatus").asText()).isEqualTo("PENDING");
        assertThat(onlyOne.output().at("/inspectionItems/0/candidates/1/status").asText()).isEqualTo("MISSING");
        var configuration = mapper.valueToTree(new TaskResultProcessingConfiguration(SCHEMA, null,
                List.of(new Label("label-target", "value", "BOOLEAN", Aggregation.REQUIRE_AGREEMENT,
                        List.of(new ValueMapping("label", mapper.getNodeFactory().booleanNode(true), mapper.getNodeFactory().nullNode()))))));
        var unknown = processor.process(set(List.of(engine(Operation.LABELING, "label", 1, false, false))), configuration);
        assertThat(unknown.output().at("/labels/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.output().at("/labels/0/value").isMissingNode()).isTrue();
    }
    @Test void rejectsIgnoredEngineResultsAndInvalidOrDuplicateMappings() {
        assertThatThrownBy(() -> processor.process(set(List.of(engine(Operation.LABELING, "extra", 1, true, false))), policy(true, false)))
                .hasMessageContaining("遗漏引擎");
        assertThatThrownBy(() -> new JudgmentMapping("one", null, PASS)).hasMessageContaining("明确映射");
        assertThatThrownBy(() -> new Check("target", List.of(new JudgmentMapping("one", FAIL, PASS), new JudgmentMapping("one", FAIL, PASS))))
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> new Label("label-target", "value", "MONTH", Aggregation.REQUIRE_AGREEMENT,
                List.of(new ValueMapping("label", mapper.getNodeFactory().numberNode(13), mapper.getNodeFactory().numberNode(1)))))
                .hasMessageContaining("类型不一致");
    }
    @Test void usesExplicitBusinessMappingsInsteadOfTreatingEveryHitAsFailure() {
        var config = new TaskResultProcessingConfiguration(SCHEMA, new Inspection(new InspectionScoring.Policy(
                InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 80,
                List.of(new InspectionScoring.Item("check-target", 10, false))), Aggregation.REQUIRE_AGREEMENT,
                List.of(new Check("check-target", List.of(new JudgmentMapping("one", PASS, FAIL),
                        new JudgmentMapping("two", FAIL, PASS))))), List.of());
        var result = processor.process(set(List.of(engine(Operation.INSPECTION, "one", 1, true, false),
                engine(Operation.INSPECTION, "two", 2, false, false))), mapper.valueToTree(config));
        assertThat(result.output().at("/scoring/finalScore").asInt()).isEqualTo(100);
        assertThat(result.output().at("/inspectionItems/0/status").asText()).isEqualTo("KNOWN");
    }
    @Test void conflictingLabelsKeepAllCandidatesWithoutChoosingTheFirstEngine() {
        var mappings = List.of(new ValueMapping("one", mapper.getNodeFactory().booleanNode(true), mapper.getNodeFactory().booleanNode(false)),
                new ValueMapping("two", mapper.getNodeFactory().booleanNode(true), mapper.getNodeFactory().booleanNode(false)));
        var config = new TaskResultProcessingConfiguration(SCHEMA, null,
                List.of(new Label("label-target", "value", "BOOLEAN", Aggregation.REQUIRE_AGREEMENT, mappings)));
        var result = processor.process(set(List.of(engine(Operation.LABELING, "one", 1, true, false),
                engine(Operation.LABELING, "two", 2, false, false))), mapper.valueToTree(config));
        assertThat(result.output().at("/labels/0/status").asText()).isEqualTo("CONFLICT");
        assertThat(result.output().at("/labels/0/candidates").size()).isEqualTo(2);
        assertThat(result.output().at("/labels/0/value").isMissingNode()).isTrue();
        assertThat(result.output().has("scoring")).isFalse();
    }
    private com.fasterxml.jackson.databind.JsonNode policy(boolean inspection, boolean labeling) {
        var quality = inspection ? new Inspection(new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,
                InspectionScoring.Mode.DEDUCTION, 100, 80, List.of(new InspectionScoring.Item("check-target", 10, false))),
                Aggregation.REQUIRE_AGREEMENT, List.of(new Check("check-target", List.of(
                new JudgmentMapping("one", FAIL, PASS), new JudgmentMapping("two", FAIL, PASS))))) : null;
        return mapper.valueToTree(new TaskResultProcessingConfiguration(SCHEMA, quality, labeling ? List.of(
                new Label("label-target", "value", "BOOLEAN", Aggregation.REQUIRE_AGREEMENT,
                        List.of(new ValueMapping("label", mapper.getNodeFactory().booleanNode(true), mapper.getNodeFactory().booleanNode(false))))) : List.of()));
    }
    private EngineResult engine(Operation operation, String instance, int sequence, boolean hit, boolean failed) {
        String target = operation == Operation.INSPECTION ? "check-target" : "label-target";
        return new EngineResult("result-" + instance, operation, instance, EngineType.REGEX, sequence, Instant.EPOCH, Instant.EPOCH,
                failed ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED, mapper.createObjectNode().put("targetId", target),
                "raw-" + instance, failed ? List.of() : List.of(new Observation(target, ObservationStatus.DETERMINED,
                mapper.getNodeFactory().booleanNode(hit), null, null, List.of())), List.of(), failed ? new Failure("TIMEOUT", "调用超时") : null);
    }
    private EngineExecutionResultSet set(List<EngineResult> engines) {
        return new EngineExecutionResultSet(SCHEMA_VERSION, "set", "task", "execution", "conversation",
                new InputSnapshot("input", Instant.EPOCH, List.of(new Message("m1", 1, "customer", null, "申请退款"))),
                mapper.createObjectNode(), engines);
    }
}
