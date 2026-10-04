package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static io.github.opensabre.iqc.scheme.SchemeDefinition.*;

/** Authoring compatibility is separate from staged runtime capability. */
class SchemeRouteProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleReference finalRule = new RuleReference("final", 1);
    private final RuleReference stageRule = new RuleReference("stage", 1);

    private Execution execution(Route route) {
        return switch (route) {
            case RULE_ONLY, LLM_ONLY -> new Execution(route, null, null, null, null);
            case RULE_THEN_LLM -> new Execution(route, stageRule, null, null, true);
            case LLM_THEN_RULE -> new Execution(route, null, stageRule, InputScope.CONVERSATION, null);
        };
    }

    private SchemeDefinition definition(Execution execution) {
        return new SchemeDefinition(SCHEMA, List.of(new Item("check", "检查", finalRule,
                HitMeaning.VIOLATION, null, null, execution)), null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION,
                        100, 60, List.of(new InspectionScoring.Item("check", 10, false))));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void allRoutesRoundTripAndUseTheDedicatedRouteEvaluator(Route route) throws Exception {
        var draft = definition(execution(route));
        var frozen = draft.forTaskSnapshot();
        assertThat(frozen.schemaVersion()).isEqualTo(ROUTED_TASK_SCHEMA);
        assertThat(frozen.forTaskSnapshot()).isSameAs(frozen);
        assertThat(mapper.readValue(mapper.writeValueAsString(frozen), SchemeDefinition.class)).isEqualTo(frozen);
        assertThatCode(draft::requireTrialExecutable).doesNotThrowAnyException();
        assertThatCode(draft::requireExecutable).doesNotThrowAnyException();
        assertThatThrownBy(draft::requireSupportedRoutes).hasMessageContaining("旧检测投影");
        assertThatThrownBy(() -> SchemeResultEvaluator.evaluate(draft, mapper.createObjectNode(), List.of(), List.of(), mapper))
                .hasMessageContaining("旧检测投影");
    }

    @Test
    void oldJsonDoesNotAcquireAnExecutionField() throws Exception {
        var legacy = definition(null);
        assertThat(mapper.writeValueAsString(legacy)).doesNotContain("execution", "inputScope");
        assertThat(mapper.readValue(mapper.writeValueAsString(legacy), SchemeDefinition.class)).isEqualTo(legacy);
        assertThat(legacy.forTaskSnapshot()).isSameAs(legacy);
        assertThatCode(legacy::requireSupportedRoutes).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingConflictingOrUnacknowledgedStages() {
        assertThatThrownBy(() -> new Execution(null, null, null, null, null)).hasMessageContaining("执行路线");
        assertThatThrownBy(() -> new Execution(Route.RULE_THEN_LLM, stageRule, null, null, false)).hasMessageContaining("违规覆盖");
        assertThatThrownBy(() -> new Execution(Route.RULE_THEN_LLM, null, null, null, true)).hasMessageContaining("初筛规则");
        assertThatThrownBy(() -> new Execution(Route.RULE_THEN_LLM, stageRule, stageRule, null, true)).hasMessageContaining("候选阶段");
        assertThatThrownBy(() -> new Execution(Route.LLM_THEN_RULE, stageRule, stageRule, null, null)).hasMessageContaining("初筛阶段");
        assertThatThrownBy(() -> new Execution(Route.LLM_THEN_RULE, null, new RuleReference("stage", 0), null, null))
                .hasMessageContaining("明确的规则版本");
        assertThatThrownBy(() -> new Execution(Route.LLM_ONLY, null, null, InputScope.MESSAGE, null)).hasMessageContaining("单阶段");
    }

    @Test
    void rejectsSelfReferenceAndCompliancePrefilter() {
        assertThatThrownBy(() -> definition(new Execution(Route.RULE_THEN_LLM, finalRule, null, null, true)))
                .hasMessageContaining("自身");
        var original = definition(execution(Route.RULE_THEN_LLM));
        assertThatThrownBy(() -> new SchemeDefinition(SCHEMA, List.of(new Item("check", "合规", finalRule,
                HitMeaning.COMPLIANCE, null, null, execution(Route.RULE_THEN_LLM))), null, original.scoring()))
                .hasMessageContaining("不能用初筛未命中证明合规");
    }

    @Test
    void routeProtocolPreservesOtherExtensionsAndRejectsSameRuleDifferentVersions() throws Exception {
        var original = definition(execution(Route.LLM_ONLY));
        var extended = new SchemeDefinition(SCHEMA, List.of(new Item("check", "检查", finalRule, HitMeaning.VIOLATION,
                new RuleReference("condition", 1), InputScope.CONVERSATION, execution(Route.LLM_ONLY))),
                new AgentReference("agent", 1), original.scoring(), new RunLimits(20, 1, 2),
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("label", 1)));
        var frozen = extended.forTaskSnapshot();
        assertThat(mapper.readValue(mapper.writeValueAsString(frozen), SchemeDefinition.class)).isEqualTo(frozen);
        assertThat(frozen.schemaVersion()).isEqualTo(ROUTED_TASK_SCHEMA);
        assertThatThrownBy(() -> new SchemeDefinition(SCHEMA, List.of(definition(execution(Route.RULE_THEN_LLM)).items().getFirst(),
                new Item("another", "另一项", new RuleReference("stage", 2), HitMeaning.VIOLATION)), null, original.scoring()))
                .hasMessageContaining("不同版本");
    }

    @Test
    void emptyRouteMarkerCannotBypassCapabilityGate() {
        var old = definition(null);
        var markerOnly = new SchemeDefinition(ROUTED_TASK_SCHEMA, old.items(), old.agent(), old.scoring());
        assertThatThrownBy(markerOnly::requireTrialExecutable).hasMessageContaining("缺少明确执行配置");
    }
}
