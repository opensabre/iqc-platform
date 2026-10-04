package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SchemeExecutionVariantsTest {
    private final SchemeDefinition.Execution direct = new SchemeDefinition.Execution(
            SchemeDefinition.Route.RULE_ONLY, null, null, null, null);

    private SchemeDefinition definition() {
        return new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(new SchemeDefinition.Item(
                "risk", "风险检查", new SchemeDefinition.RuleReference("r1", 1), SchemeDefinition.HitMeaning.VIOLATION)),
                null, new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,
                InspectionScoring.Mode.DEDUCTION, 100, 60, List.of()));
    }

    private SchemeExecutionVariants.Variant variant(String code, Map<String, SchemeDefinition.Execution> routes) {
        return new SchemeExecutionVariants.Variant(code, "标准检查", "覆盖全部项目", "不调用模型", routes);
    }

    @Test
    void defaultSelectionExpandsOnlyRoutesAndUsesExistingProtocolBoundary() throws Exception {
        var variants = new SchemeExecutionVariants("standard", List.of(variant("standard", Map.of("risk", direct))));
        var original = definition();
        var expanded = variants.expand(original, null);
        assertThat(expanded.items().getFirst().execution()).isEqualTo(direct);
        assertThat(expanded.scoring()).isSameAs(original.scoring());
        assertThat(expanded.items().getFirst().rule()).isEqualTo(original.items().getFirst().rule());
        assertThat(original.items().getFirst().execution()).isNull();
        assertThat(expanded.forTaskSnapshot().schemaVersion()).isEqualTo(SchemeDefinition.ROUTED_TASK_SCHEMA);
        assertThatCode(expanded::requireExecutable).doesNotThrowAnyException();
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(variants), SchemeExecutionVariants.class)).isEqualTo(variants);
    }

    @Test
    void unknownAndBlankCodesDoNotSilentlySelectRecommendation() {
        var variants = new SchemeExecutionVariants("standard", List.of(variant("standard", Map.of("risk", direct))));
        assertThatThrownBy(() -> variants.expand(definition(), "fast")).hasMessageContaining("允许变体");
        assertThatThrownBy(() -> variants.select(" ")).hasMessageContaining("允许变体");
    }

    @Test
    void rejectsIncompleteOrInventedCoverageIncludingUnselectedAlternatives() {
        var variants = new SchemeExecutionVariants("standard", List.of(
                variant("standard", Map.of("risk", direct)), variant("bad", Map.of("other", direct))));
        assertThatThrownBy(() -> variants.expand(definition(), "standard")).hasMessageContaining("完整覆盖");
        var extra = new SchemeExecutionVariants("extra", List.of(variant("extra", Map.of("risk", direct, "other", direct))));
        assertThatThrownBy(() -> extra.validate(definition())).hasMessageContaining("完整覆盖");
    }

    @Test
    void rejectsDuplicateCodesAndUnknownRecommendation() {
        var standard = variant("standard", Map.of("risk", direct));
        assertThatThrownBy(() -> new SchemeExecutionVariants("standard", List.of(standard, standard)))
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> new SchemeExecutionVariants("missing", List.of(standard)))
                .hasMessageContaining("推荐策略");
    }

    @Test
    void preservesPersistedRouteMapOrderAcrossSnapshotRoundTrips() throws Exception {
        var mapper = new ObjectMapper();
        String persisted = """
                {"recommendedCode":"standard","variants":[{"code":"standard","name":"标准检查",
                 "coverage":"全部项目","cost":"不调用模型","routes":{
                   "item_z":{"route":"RULE_ONLY"},"item_a":{"route":"RULE_ONLY"},"item_m":{"route":"RULE_ONLY"}}}]}
                """;
        String firstRound = mapper.writeValueAsString(mapper.readValue(persisted, SchemeExecutionVariants.class));
        String secondRound = mapper.writeValueAsString(mapper.readValue(firstRound, SchemeExecutionVariants.class));
        assertThat(secondRound).isEqualTo(firstRound);
        var snapshot = mapper.readTree(firstRound);
        var routeCodes = new ArrayList<String>();
        snapshot.at("/variants/0/routes").fieldNames().forEachRemaining(routeCodes::add);
        assertThat(routeCodes).containsExactly("item_z", "item_a", "item_m");
    }

    @Test
    void existingItemValidationRejectsStageSelfReference() {
        var candidate = new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_THEN_RULE, null,
                new SchemeDefinition.RuleReference("r1", 1), null, null);
        var variants = new SchemeExecutionVariants("candidate", List.of(variant("candidate", Map.of("risk", candidate))));
        assertThatThrownBy(() -> variants.expand(definition(), null)).hasMessageContaining("自身");
    }

    @Test
    void templatePersistsAlternativesWithoutChangingLegacyJsonOrOpeningPublication() throws Exception {
        var original = definition();
        var variants = new SchemeExecutionVariants("standard", List.of(variant("standard", Map.of("risk", direct))));
        var authored = new SchemeDefinition(original.schemaVersion(), original.items(), original.agent(), original.scoring(),
                original.runLimits(), original.labels(), variants);
        var mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(original)).doesNotContain("executionVariants");
        assertThat(mapper.readValue(mapper.writeValueAsString(authored), SchemeDefinition.class)).isEqualTo(authored);
        assertThatThrownBy(authored::requireExecutable).hasMessageContaining("尚未展开");
        assertThatThrownBy(authored::requireTrialExecutable).hasMessageContaining("尚未展开");
        var expanded = variants.expand(authored, null);
        var frozen = new SchemeDefinition(expanded.schemaVersion(), expanded.items(), expanded.agent(), expanded.scoring(),
                expanded.runLimits(), expanded.labels(), variants).forTaskSnapshot();
        assertThat(frozen.executionVariants()).isEqualTo(variants);
        frozen.requireTrialExecutable();
        assertThat(frozen.forTaskSnapshot()).isSameAs(frozen);
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "scoped", "conditional", "joint"})
    void unexpandedVariantsNeverDisappearDuringTaskProtocolNormalization(String kind) throws Exception {
        var original = definition();
        var llmOnly = new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_ONLY, null, null, null, null);
        var variants = new SchemeExecutionVariants("standard", List.of(variant("standard", Map.of("risk", llmOnly))));
        var scope = "scoped".equals(kind) ? SchemeDefinition.InputScope.CONVERSATION : null;
        var condition = "conditional".equals(kind) ? new SchemeDefinition.RuleReference("gate", 1) : null;
        var labels = "joint".equals(kind)
                ? List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("label", 1)) : null;
        var authored = new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(new SchemeDefinition.Item(
                "risk", "风险检查", new SchemeDefinition.RuleReference("r1", 1), SchemeDefinition.HitMeaning.VIOLATION,
                condition, scope)), null, original.scoring(), null, labels, variants);
        var frozen = authored.forTaskSnapshot();
        assertThat(frozen.schemaVersion()).isEqualTo(SchemeDefinition.ROUTED_TASK_SCHEMA);
        assertThat(frozen.executionVariants()).isEqualTo(variants);
        assertThat(frozen.items()).isEqualTo(authored.items());
        assertThat(frozen.labels()).isEqualTo(labels);
        assertThat(frozen.scoring()).isSameAs(authored.scoring());
        assertThat(frozen.forTaskSnapshot()).isSameAs(frozen);
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(frozen), SchemeDefinition.class);
        assertThat(restored).isEqualTo(frozen);
        assertThatThrownBy(restored::requireTrialExecutable).hasMessageContaining("尚未展开");
        assertThatThrownBy(restored::requireExecutable).hasMessageContaining("尚未展开");
    }
}
