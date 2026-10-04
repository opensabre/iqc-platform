package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SchemeResultEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void explicitScopeUsesANewTaskProtocolAndNeverInventsCitations() throws Exception {
        var original = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        var item = original.items().getFirst();
        var scoped = new SchemeDefinition(original.schemaVersion(), List.of(new SchemeDefinition.Item(item.itemCode(),
                item.name(), item.rule(), item.hitMeaning(), null, SchemeDefinition.InputScope.CONVERSATION)),
                null, original.scoring());
        assertThat(mapper.writeValueAsString(original)).doesNotContain("inputScope");
        var frozen = scoped.forTaskSnapshot();
        assertThat(frozen.schemaVersion()).isEqualTo(SchemeDefinition.SCOPED_TASK_SCHEMA);
        assertThat(frozen.forTaskSnapshot()).isSameAs(frozen);
        assertThat(mapper.readValue(mapper.writeValueAsString(frozen), SchemeDefinition.class)).isEqualTo(frozen);
        assertThatCode(scoped::requireExecutable).doesNotThrowAnyException();
        var snapshot = snapshot(frozen);
        ((ObjectNode) snapshot.path("rules").get(0)).put("inspectionScope", "CONVERSATION");
        var evaluated = SchemeResultEvaluator.evaluate(frozen, snapshot, List.of(message("m1", "agent")),
                List.of(result("m1", "HIT")), mapper);
        assertThat(evaluated.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.PASS);
        assertThat(evaluated.items().getFirst().matchedMessageIds()).isEmpty();
    }

    private SchemeDefinition conditionalDefinition() {
        var original = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        return new SchemeDefinition(original.schemaVersion(), List.of(new SchemeDefinition.Item("greeting", "开场白",
                new SchemeDefinition.RuleReference("r1", 1), SchemeDefinition.HitMeaning.COMPLIANCE,
                new SchemeDefinition.RuleReference("entered-sales", 1))), null, original.scoring());
    }

    private ObjectNode conditionalSnapshot() {
        var snapshot = snapshot(conditionalDefinition());
        snapshot.withArray("rules").addObject().put("id", "entered-sales").put("versionNo", 1).put("targetRole", "customer");
        return snapshot;
    }

    private InspectionResult conditionResult(String status) {
        var result = result("customer-message", status);
        result.setRuleBreakdownJson("[{\"ruleId\":\"entered-sales\",\"status\":\"" + status + "\"}]");
        return result;
    }

    @Test
    void jointTaskProtocolPreservesHistoricalReadCompatibilityAndFreezesLabelsExplicitly() throws Exception {
        var original = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        var joint = new SchemeDefinition(original.schemaVersion(), original.items(), original.agent(), original.scoring(), null,
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("loan", 1)));
        var historicalJson = mapper.writeValueAsString(joint);
        assertThat(mapper.readValue(historicalJson, SchemeDefinition.class)).isEqualTo(joint);
        var frozen = joint.forTaskSnapshot();
        assertThat(joint.schemaVersion()).isEqualTo(SchemeDefinition.SCHEMA);
        assertThat(frozen.schemaVersion()).isEqualTo(SchemeDefinition.JOINT_TASK_SCHEMA);
        assertThat(frozen.labels()).isEqualTo(joint.labels());
        assertThat(frozen.forTaskSnapshot()).isSameAs(frozen);
        assertThat(mapper.readValue(mapper.writeValueAsString(frozen), SchemeDefinition.class)).isEqualTo(frozen);
        var conditional = conditionalDefinition();
        var both = new SchemeDefinition(conditional.schemaVersion(), conditional.items(), conditional.agent(), conditional.scoring(), null, joint.labels());
        assertThat(both.forTaskSnapshot().schemaVersion()).isEqualTo(SchemeDefinition.JOINT_TASK_SCHEMA);
        assertThat(both.forTaskSnapshot().items()).isEqualTo(conditional.items());
    }

    @Test
    void conditionalTaskProtocolIsExplicitWhileHistoricalDefinitionsRemainByteCompatible() throws Exception {
        var historical = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        assertThat(historical.forTaskSnapshot()).isSameAs(historical);
        assertThat(mapper.writeValueAsString(historical.forTaskSnapshot())).isEqualTo(mapper.writeValueAsString(historical));
        var authored = conditionalDefinition();
        var frozen = authored.forTaskSnapshot();
        assertThat(authored.schemaVersion()).isEqualTo(SchemeDefinition.SCHEMA);
        assertThat(frozen.schemaVersion()).isEqualTo(SchemeDefinition.CONDITIONAL_TASK_SCHEMA);
        assertThat(frozen.forTaskSnapshot()).isEqualTo(frozen);
        assertThat(mapper.readValue(mapper.writeValueAsString(frozen), SchemeDefinition.class)).isEqualTo(frozen);
        var snapshot = conditionalSnapshot();
        ((ObjectNode) snapshot.path("schemeSnapshot").path("release")).set("definition", mapper.valueToTree(frozen));
        assertThat(SchemeResultEvaluator.definition(snapshot, mapper)).isEqualTo(frozen);
        ((ObjectNode) snapshot.path("schemeSnapshot").path("release").path("definition"))
                .put("schemaVersion", "unsupported-future-version");
        assertThatThrownBy(() -> SchemeResultEvaluator.definition(snapshot, mapper)).hasMessageContaining("禁止回退旧评分");
    }

    @Test
    void definiteNegativeBusinessConditionIsNotApplicableEvenWhenCheckWasNotEvaluated() {
        var evaluation = SchemeResultEvaluator.evaluate(conditionalDefinition(), conditionalSnapshot(),
                List.of(message("customer-message", "customer"), message("agent-message", "agent")),
                List.of(conditionResult("NOT_HIT")), mapper);
        assertThat(evaluation.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.NOT_APPLICABLE);
        assertThat(evaluation.items().getFirst().matchedMessageIds()).isEmpty();
        assertThat(evaluation.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.NOT_APPLICABLE);
        assertThat(evaluation.scoring().finalScore()).isNull();
    }

    @Test
    void positiveConditionEvaluatesCheckWithoutCountingTriggerAsCheckEvidence() {
        var check = result("agent-message", "NOT_HIT");
        var evaluation = SchemeResultEvaluator.evaluate(conditionalDefinition(), conditionalSnapshot(),
                List.of(message("customer-message", "customer"), message("agent-message", "agent")),
                List.of(conditionResult("HIT"), check), mapper);
        assertThat(evaluation.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.FAIL);
        assertThat(evaluation.items().getFirst().matchedMessageIds()).isEmpty();
        assertThat(evaluation.scoring().finalScore()).isEqualByComparingTo("90");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED"})
    void unresolvedConditionNeverBecomesNotApplicableOrFinalScore(String status) {
        var evaluation = SchemeResultEvaluator.evaluate(conditionalDefinition(), conditionalSnapshot(),
                List.of(message("customer-message", "customer")), List.of(conditionResult(status)), mapper);
        assertThat(evaluation.items().getFirst().status().name()).isEqualTo(status);
        assertThat(evaluation.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.PENDING);
    }

    @Test
    void gateRequiresCompleteCoverageEvenIfOneMessageHits() {
        var evaluation = SchemeResultEvaluator.evaluate(conditionalDefinition(), conditionalSnapshot(),
                List.of(message("customer-message", "customer"), message("missing", "customer")),
                List.of(conditionResult("HIT")), mapper);
        assertThat(evaluation.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.NOT_EVALUATED);
        assertThatThrownBy(() -> SchemeResultEvaluator.evaluate(conditionalDefinition(), snapshot(conditionalDefinition()),
                List.of(), List.of(), mapper)).hasMessageContaining("适用条件");
    }

    @Test
    void conditionalDefinitionPreservesOldJsonAndRejectsInvalidOrSelfReferences() throws Exception {
        assertThat(mapper.valueToTree(definition(SchemeDefinition.HitMeaning.COMPLIANCE)).path("items").get(0).has("appliesWhen")).isFalse();
        assertThat(mapper.readValue(mapper.writeValueAsString(conditionalDefinition()), SchemeDefinition.class)).isEqualTo(conditionalDefinition());
        conditionalDefinition().requireExecutable();
        for (var reference : List.of(new SchemeDefinition.RuleReference("r1", 1), new SchemeDefinition.RuleReference("", 1),
                new SchemeDefinition.RuleReference("entered-sales", 0))) {
            assertThatThrownBy(() -> new SchemeDefinition(SchemeDefinition.SCHEMA,
                    List.of(new SchemeDefinition.Item("greeting", "开场白", new SchemeDefinition.RuleReference("r1", 1),
                            SchemeDefinition.HitMeaning.COMPLIANCE, reference)), null, conditionalDefinition().scoring()))
                    .isInstanceOf(io.github.opensabre.iqc.governance.IqcException.class);
        }
    }

    @Test
    void hitMeaningSeparatesComplianceFromViolationAndIgnoresDetectorPrice() {
        var compliance = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        var violation = definition(SchemeDefinition.HitMeaning.VIOLATION);
        var pass = evaluate(compliance, List.of(message("m1", "agent")), List.of(result("m1", "HIT")));
        var fail = evaluate(violation, List.of(message("m1", "agent")), List.of(result("m1", "HIT")));
        assertThat(pass.scoring().finalScore()).isEqualByComparingTo("100");
        assertThat(fail.scoring().finalScore()).isEqualByComparingTo("90");
        assertThat(fail.items().getFirst().matchedMessageIds()).containsExactly("m1");
    }

    @Test
    void conversationFactUsesCitedMessageInsteadOfOwnerSliceForScoreEvidence() {
        var definition = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        var snapshot = snapshot(definition);
        var rule = (ObjectNode) snapshot.path("rules").get(0);
        rule.put("ruleType", "LLM").put("targetRole", "all");
        rule.putArray("labelFactTargets").add("house.owns");
        var owner = result("m1", "HIT");
        owner.setEvidenceJson("[{\"ruleId\":\"r1\",\"messageId\":\"m2\",\"text\":\"没有房子\"}]");
        var repeated = result("m2", "HIT");
        repeated.setEvidenceJson("[]");

        var evaluation = SchemeResultEvaluator.evaluate(definition, snapshot,
                List.of(message("m1", "agent"), message("m2", "customer")), List.of(owner, repeated), mapper);

        assertThat(evaluation.items().getFirst().matchedMessageIds()).containsExactly("m2");
        assertThat(evaluation.scoring().finalScore()).isEqualByComparingTo("100");
    }

    @Test
    void conversationFactWithoutCitationDoesNotFabricateOwnerMessageMatch() {
        var definition = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        var snapshot = snapshot(definition);
        var rule = (ObjectNode) snapshot.path("rules").get(0);
        rule.put("ruleType", "LLM");
        rule.putArray("labelFactTargets").add("house.owns");

        var evaluation = SchemeResultEvaluator.evaluate(definition, snapshot,
                List.of(message("m1", "agent")), List.of(result("m1", "HIT")), mapper);

        assertThat(evaluation.items().getFirst().matchedMessageIds()).isEmpty();
        assertThat(evaluation.scoring().finalScore()).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED", "unknown"})
    void unresolvedObservationDoesNotProduceFinalScoreEvenWhenAnotherMessageHits(String status) {
        var evaluation = evaluate(definition(SchemeDefinition.HitMeaning.COMPLIANCE),
                List.of(message("m1", "agent"), message("m2", "agent")), List.of(result("m1", "HIT"), result("m2", status)));
        assertThat(evaluation.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.PENDING);
        assertThat(evaluation.scoring().finalScore()).isNull();
    }

    @Test
    void missingMessageIsPendingInsteadOfPass() {
        var evaluation = evaluate(definition(SchemeDefinition.HitMeaning.VIOLATION),
                List.of(message("m1", "agent"), message("m2", "agent")), List.of(result("m1", "NOT_HIT")));
        assertThat(evaluation.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.NOT_EVALUATED);
        assertThat(evaluation.scoring().finalScore()).isNull();
    }

    @Test
    void noApplicableSpeakerIsNotApplicableRatherThanComplianceFailure() {
        var evaluation = evaluate(definition(SchemeDefinition.HitMeaning.COMPLIANCE), List.of(message("m1", "customer")), List.of());
        assertThat(evaluation.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.NOT_APPLICABLE);
        assertThat(evaluation.scoring().finalScore()).isNull();
    }

    @Test
    void emptyConversationIsNotEvaluatedRatherThanNotApplicable() {
        assertThat(evaluate(definition(SchemeDefinition.HitMeaning.COMPLIANCE), List.of(), List.of()).items().getFirst().status())
                .isEqualTo(InspectionScoring.ItemStatus.NOT_EVALUATED);
    }

    @Test
    void malformedAndDuplicateObservationsAreErrors() {
        InspectionResult malformed = result("m1", "HIT"); malformed.setRuleBreakdownJson("bad-json");
        InspectionResult duplicate = result("m1", "HIT");
        duplicate.setRuleBreakdownJson("[{\"ruleId\":\"r1\",\"status\":\"HIT\"},{\"ruleId\":\"r1\",\"status\":\"NOT_HIT\"}]");
        for (var observation : List.of(malformed, duplicate)) {
            assertThat(evaluate(definition(SchemeDefinition.HitMeaning.COMPLIANCE), List.of(message("m1", "agent")), List.of(observation))
                    .items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.ERROR);
        }
    }

    @Test
    void malformedExplicitSchemeAndMismatchedDependencyCannotUseLegacyScoring() {
        assertThat(SchemeResultEvaluator.definition(mapper.createObjectNode(), mapper)).isNull();
        assertThatThrownBy(() -> SchemeResultEvaluator.definition(mapper.createObjectNode().putNull("schemeSnapshot"), mapper))
                .hasMessageContaining("禁止回退");
        var definition = definition(SchemeDefinition.HitMeaning.COMPLIANCE);
        var snapshot = snapshot(definition); ((ObjectNode) snapshot.path("rules").get(0)).put("versionNo", 2);
        assertThatThrownBy(() -> SchemeResultEvaluator.evaluate(definition, snapshot, List.of(), List.of(), mapper))
                .hasMessageContaining("不一致");
    }

    private SchemeResultEvaluator.Evaluation evaluate(SchemeDefinition definition, List<ConversationMessage> messages, List<InspectionResult> results) {
        return SchemeResultEvaluator.evaluate(definition, snapshot(definition), messages, results, mapper);
    }

    private ObjectNode snapshot(SchemeDefinition definition) {
        var root = mapper.createObjectNode();
        root.putArray("rules").addObject().put("id", "r1").put("versionNo", 1).put("targetRole", "agent").put("deduction", 99).put("veto", true);
        root.putObject("schemeSnapshot").putObject("release").set("definition", mapper.valueToTree(definition));
        return root;
    }

    private SchemeDefinition definition(SchemeDefinition.HitMeaning meaning) {
        return new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("greeting", "开场白", new SchemeDefinition.RuleReference("r1", 1), meaning)), null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new InspectionScoring.Item("greeting", 10, false))));
    }

    private ConversationMessage message(String id, String role) {
        var value = new ConversationMessage(); value.setId(id); value.setSpeakerRole(role); return value;
    }

    private InspectionResult result(String id, String status) {
        var value = new InspectionResult(); value.setMessageId(id); value.setResultStatus(status);
        value.setRuleBreakdownJson("[{\"ruleId\":\"r1\",\"status\":\"" + status + "\"}]"); return value;
    }
}
