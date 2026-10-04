package io.github.opensabre.iqc.result;

import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.scheme.SchemeDependencyResolver.*;
import io.github.opensabre.iqc.scheme.SchemeDefinition.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Core orchestration tests use controlled stage callbacks, not a live model or persistence fixture. */
class ItemRouteRunnerTest {
    private DetectionContext context(String key, String phase, String upstream) {
        return new DetectionContext(key, key, 1, "CONVERSATION", phase, upstream);
    }
    private ConversationMessage message(String id, String text) {
        var value = new ConversationMessage(); value.setId(id); value.setContent(text);
        value.setConversationId("conversation"); value.setSpeakerRole("customer"); return value;
    }
    private InspectionResult result(String status) {
        var value = new InspectionResult(); value.setResultStatus(status); value.setDeduction(30); value.setEvidenceJson("[]"); return value;
    }
    private RoutePlan paired(Route route) {
        return new RoutePlan("iqc-item-execution-plan-v1", List.of(
                context("stage", route == Route.RULE_THEN_LLM ? "PREFILTER" : "CANDIDATE", null),
                context("final", route == Route.RULE_THEN_LLM ? "REVIEW" : "VERIFY", "stage")),
                List.of(new ItemRoute("check", route, "stage", "final")));
    }

    private RoutePlan gated() {
        return new RoutePlan("iqc-item-execution-plan-v2", List.of(context("gate", "APPLICABILITY", null),
                context("final", "DIRECT", null)), List.of(new ItemRoute("check", Route.RULE_ONLY, null, "final", "gate")));
    }

    @Test
    void noPrefilterHitDoesNotSkipIndependentRiskAndIntentionChecks() {
        var pair = paired(Route.RULE_THEN_LLM); var contexts = new ArrayList<>(pair.contexts());
        contexts.add(context("risk", "DIRECT", null)); contexts.add(context("intention", "DIRECT", null));
        var items = new ArrayList<>(pair.items()); items.add(new ItemRoute("risk", Route.LLM_ONLY, null, "risk"));
        items.add(new ItemRoute("intention", Route.LLM_ONLY, null, "intention"));
        var calls = new ArrayList<String>();
        var run = ItemRouteRunner.execute(new RoutePlan(pair.schemaVersion(), contexts, items), List.of(message("m1", "我想贷款")),
                (context, inputs, upstream) -> { calls.add(context.contextKey()); return result(context.contextKey().equals("stage") ? "NOT_HIT" : "HIT"); },
                candidate -> { throw new AssertionError("not a candidate route"); });
        assertThat(calls).containsExactly("stage", "risk", "intention");
        assertThat(run.items()).extracting(item -> item.terminal().result().getResultStatus()).containsExactly("NOT_HIT", "HIT", "HIT");
        assertThat(run.items().getFirst().terminal().executed()).isFalse();
        assertThat(run.items().get(1).terminal().executed()).isTrue();
    }

    @Test
    void sharedStagesExecuteOnceAndConsumersCannotMutateSharedResults() {
        var pair = paired(Route.RULE_THEN_LLM); var calls = new ArrayList<String>();
        var plan = new RoutePlan(pair.schemaVersion(), pair.contexts(), List.of(pair.items().getFirst(),
                new ItemRoute("another", Route.RULE_THEN_LLM, "stage", "final")));
        var upstream = result("HIT");
        var run = ItemRouteRunner.execute(plan, List.of(message("m1", "引用")), (context, inputs, prior) -> {
            calls.add(context.contextKey());
            if (prior != null) { assertThat(prior.getResultStatus()).isEqualTo("HIT"); prior.setResultStatus("CANDIDATE"); }
            return context.contextKey().equals("stage") ? upstream : result("NOT_HIT");
        }, candidate -> null);
        assertThat(calls).containsExactly("stage", "final");
        assertThat(upstream.getResultStatus()).isEqualTo("HIT");
        assertThat(run.items().getFirst().intermediate().result().getResultStatus()).isEqualTo("HIT");
        assertThat(run.items().getFirst().terminal().result().getDeduction()).isZero();
        run.items().getFirst().terminal().result().setResultStatus("HIT");
        assertThat(run.items().get(1).terminal().result().getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(run.stages().get(1).result().getResultStatus()).isEqualTo("NOT_HIT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED", "UNKNOWN"})
    void uncertainOrFailedStageCannotBecomeNoMatch(String status) {
        var calls = new ArrayList<String>();
        var run = ItemRouteRunner.execute(paired(Route.RULE_THEN_LLM), List.of(message("m1", "内容")),
                (context, inputs, upstream) -> { calls.add(context.contextKey()); return result(status); }, candidate -> null);
        assertThat(calls).containsExactly("stage");
        assertThat(run.items().getFirst().terminal().result().getResultStatus())
                .isEqualTo(status.equals("ERROR") ? "ERROR" : "REVIEW_REQUIRED");
        assertThat(run.items().getFirst().terminal().executed()).isFalse();
    }

    @Test
    void candidateVerificationReceivesOnlyValidatedQuoteNotEntireConversation() {
        var calls = new ArrayList<String>(); var source = message("m1", "前文无关，我想贷款，后文无关");
        var run = ItemRouteRunner.execute(paired(Route.LLM_THEN_RULE), List.of(source), (context, inputs, upstream) -> {
            calls.add(context.contextKey());
            if (context.phase().equals("VERIFY")) {
                assertThat(inputs).hasSize(1); assertThat(inputs.getFirst().getContent()).isEqualTo("我想贷款");
                assertThat(inputs.getFirst().getId()).isEqualTo("m1"); assertThat(upstream.getResultStatus()).isEqualTo("HIT");
            }
            return result("HIT");
        }, candidate -> List.of(message("m1", "我想贷款")));
        assertThat(calls).containsExactly("stage", "final");
        assertThat(run.items().getFirst().terminal().executed()).isTrue();
        assertThat(source.getContent()).isEqualTo("前文无关，我想贷款，后文无关");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invented", "outside", "role", "conversation", "empty"})
    void invalidCandidateCitationRequiresReviewWithoutFallback(String invalid) {
        var calls = new ArrayList<String>();
        var run = ItemRouteRunner.execute(paired(Route.LLM_THEN_RULE), List.of(message("m1", "我想贷款")),
                (context, inputs, upstream) -> { calls.add(context.contextKey()); return result("HIT"); }, candidate -> {
                    var quote = message(invalid.equals("outside") ? "other" : "m1", invalid.equals("invented") ? "编造内容" : "我想贷款");
                    if (invalid.equals("role")) quote.setSpeakerRole("agent");
                    if (invalid.equals("conversation")) quote.setConversationId("other");
                    if (invalid.equals("empty")) quote.setContent(" ");
                    return invalid.equals("missing") ? List.of() : List.of(quote);
                });
        assertThat(calls).containsExactly("stage");
        assertThat(run.items().getFirst().terminal().result().getResultStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void adapterExceptionIsIsolatedAndContextCacheDoesNotLeakAcrossRuns() {
        var plan = new RoutePlan("iqc-item-execution-plan-v1", List.of(context("a", "DIRECT", null), context("b", "DIRECT", null)),
                List.of(new ItemRoute("a", Route.RULE_ONLY, null, "a"), new ItemRoute("b", Route.LLM_ONLY, null, "b")));
        var calls = new ArrayList<String>();
        ItemRouteRunner.Detector detector = (context, inputs, upstream) -> {
            calls.add(context.contextKey());
            if (context.contextKey().equals("a")) throw new IllegalStateException("secret-value");
            return result("HIT");
        };
        var first = ItemRouteRunner.execute(plan, List.of(message("m1", "内容")), detector, candidate -> null);
        assertThat(first.items().getFirst().terminal().result().getReason()).doesNotContain("secret-value");
        assertThat(first.items().get(1).terminal().result().getResultStatus()).isEqualTo("HIT");
        ItemRouteRunner.execute(plan, List.of(message("m2", "另一会话运行")), detector, candidate -> null);
        assertThat(calls).containsExactly("a", "b", "a", "b");
    }

    @Test
    void malformedDependencyPlanFailsBeforeAnyAdapter() {
        var plan = new RoutePlan("iqc-item-execution-plan-v1", List.of(context("final", "VERIFY", "missing")),
                List.of(new ItemRoute("a", Route.LLM_THEN_RULE, "missing", "final")));
        assertThatThrownBy(() -> ItemRouteRunner.execute(plan, List.of(), (context, inputs, upstream) -> {
            throw new AssertionError("must not call");
        }, candidate -> null)).hasMessageContaining("缺失阶段");
    }

    @Test
    void callbacksCannotRewriteSharedMessageInputsOrReturnUndefinedStatusAsPass() {
        var plan = new RoutePlan("iqc-item-execution-plan-v1", List.of(context("a", "DIRECT", null), context("b", "DIRECT", null)),
                List.of(new ItemRoute("a", Route.RULE_ONLY, null, "a"), new ItemRoute("b", Route.LLM_ONLY, null, "b")));
        var source = message("m1", "原始内容");
        var run = ItemRouteRunner.execute(plan, List.of(source), (context, inputs, upstream) -> {
            assertThat(inputs.getFirst().getContent()).isEqualTo("原始内容");
            inputs.getFirst().setContent("适配器改动");
            var undefined = new InspectionResult(); return undefined;
        }, candidate -> null);
        assertThat(source.getContent()).isEqualTo("原始内容");
        assertThat(run.items()).extracting(item -> item.terminal().result().getResultStatus())
                .containsExactly("REVIEW_REQUIRED", "REVIEW_REQUIRED");
    }

    @Test
    void multiRoundAdjudicationRepeatsTheWholeRouteAndUsesOnlyTerminalVotes() throws Exception {
        var calls = new ArrayList<String>();
        var detector = new ItemRouteRunner.Detector() {
            @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs, InspectionResult upstream) {
                throw new AssertionError("round index must be provided");
            }
            @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                       InspectionResult upstream, int runIndex) {
                calls.add(context.contextKey());
                if (context.phase().equals("REVIEW")) assertThat(upstream.getResultStatus()).isEqualTo("HIT");
                return result(context.phase().equals("PREFILTER") || runIndex != 1 ? "HIT" : "NOT_HIT");
            }
        };
        var run = ItemRouteRunner.execute(paired(Route.RULE_THEN_LLM), List.of(message("m1", "内容")), 3, null, detector, candidate -> null);
        assertThat(calls).containsExactly("stage", "final", "stage", "final", "stage", "final");
        var terminal = run.items().getFirst().terminal().result();
        assertThat(terminal.getResultStatus()).isEqualTo("HIT");
        var finding = new com.fasterxml.jackson.databind.ObjectMapper().readTree(terminal.getFindingJson());
        assertThat(finding.path("schemaVersion").asText()).isEqualTo("iqc-route-round-v1");
        assertThat(finding.path("runCount").asInt()).isEqualTo(3);
        assertThat(finding.path("hitCount").asInt()).isEqualTo(2);
        assertThat(finding.path("runs")).hasSize(3);
    }

    @Test
    void tieLowAgreementAndAnyFailedRoundStayUnresolved() {
        var plan = new RoutePlan("iqc-item-execution-plan-v1", List.of(context("direct", "DIRECT", null)),
                List.of(new ItemRoute("item", Route.LLM_ONLY, null, "direct")));
        var alternating = new ItemRouteRunner.Detector() {
            @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs, InspectionResult upstream) { return result("ERROR"); }
            @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs, InspectionResult upstream, int runIndex) {
                return result(runIndex == 0 ? "HIT" : "NOT_HIT");
            }
        };
        var tied = ItemRouteRunner.execute(plan, List.of(message("m1", "内容")), 2, null, alternating, candidate -> null);
        assertThat(tied.items().getFirst().terminal().result().getResultStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(tied.items().getFirst().terminal().result().getReason()).contains("多轮裁决未达到确定结论");
        var uncertain = ItemRouteRunner.execute(plan, List.of(message("m1", "内容")), 3, new java.math.BigDecimal("0.80"),
                new ItemRouteRunner.Detector() {
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs, InspectionResult upstream) { return result("ERROR"); }
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream, int runIndex) {
                        return result(runIndex == 2 ? "NOT_HIT" : "HIT");
                    }
                }, candidate -> null);
        assertThat(uncertain.items().getFirst().terminal().result().getResultStatus()).isEqualTo("REVIEW_REQUIRED");
        var failed = ItemRouteRunner.execute(plan, List.of(message("m1", "内容")), 3, null,
                new ItemRouteRunner.Detector() {
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs, InspectionResult upstream) { return result("ERROR"); }
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream, int runIndex) {
                        return result(runIndex == 1 ? "ERROR" : "HIT");
                    }
                }, candidate -> null);
        assertThat(failed.items().getFirst().terminal().result().getResultStatus()).isEqualTo("ERROR");
    }

    @Test
    void applicabilityFalseSkipsRouteAndIsNotApplicableInsteadOfPass() {
        var calls = new ArrayList<String>();
        var run = ItemRouteRunner.execute(gated(), List.of(message("m1", "内容")), (context, inputs, upstream) -> {
            calls.add(context.contextKey()); return result("NOT_HIT");
        }, candidate -> null);
        assertThat(calls).containsExactly("gate");
        assertThat(run.items().getFirst().adjudicatedStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(run.items().getFirst().terminal()).isNull();
        assertThat(run.stages()).extracting(stage -> stage.contextKey() + ":" + stage.result().getResultStatus())
                .containsExactly("gate:NOT_HIT", "final:NOT_EVALUATED");
    }

    @Test
    void applicabilityErrorsAndUncertaintyDoNotRunOrPassTheBusinessRoute() {
        for (String gateStatus : List.of("ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED")) {
            var calls = new ArrayList<String>();
            var run = ItemRouteRunner.execute(gated(), List.of(message("m1", "内容")), (context, inputs, upstream) -> {
                calls.add(context.contextKey()); return result(gateStatus);
            }, candidate -> null);
            assertThat(calls).containsExactly("gate");
            assertThat(run.items().getFirst().adjudicatedStatus()).isEqualTo(gateStatus.equals("ERROR")
                    ? "ERROR" : gateStatus.equals("REVIEW_REQUIRED") ? "REVIEW_REQUIRED" : "NOT_EVALUATED");
        }
    }

    @Test
    void multiroundApplicabilityCountsNotApplicableAsItsOwnFinalVote() throws Exception {
        var calls = new ArrayList<String>();
        var run = ItemRouteRunner.execute(gated(), List.of(message("m1", "内容")), 3, null,
                new ItemRouteRunner.Detector() {
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream) { return result("ERROR"); }
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream, int runIndex) {
                        calls.add(context.contextKey() + runIndex);
                        if ("gate".equals(context.contextKey())) return result(runIndex < 2 ? "HIT" : "NOT_HIT");
                        var value = result("HIT");
                        value.setEvidenceJson("[{\"ruleId\":\"final\",\"messageId\":\"m1\",\"text\":\"内容\"}]");
                        return value;
                    }
                }, candidate -> null);
        assertThat(calls).containsExactly("gate0", "final0", "gate1", "final1", "gate2");
        var item = run.items().getFirst();
        assertThat(item.adjudicatedStatus()).isEqualTo("HIT");
        assertThat(item.terminal().result().getResultStatus()).isEqualTo("HIT");
        var vote = new com.fasterxml.jackson.databind.ObjectMapper().readTree(item.terminal().result().getFindingJson());
        assertThat(vote.path("schemaVersion").asText()).isEqualTo("iqc-route-item-vote-v1");
        assertThat(vote.path("runCount").asInt()).isEqualTo(3);
        assertThat(vote.path("notApplicableCount").asInt()).isEqualTo(1);
        assertThat(com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(item.terminal().result().getEvidenceJson()))
                .hasSize(1);
    }

    @Test
    void multiroundApplicabilityTieNeedsReviewAndMajorityCanBeNotApplicable() throws Exception {
        var split = ItemRouteRunner.execute(gated(), List.of(message("m1", "内容")), 2, null,
                new ItemRouteRunner.Detector() {
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream) { return result("ERROR"); }
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream, int runIndex) {
                        return result(runIndex == 0 ? "HIT" : "NOT_HIT");
                    }
                }, candidate -> null);
        assertThat(split.items().getFirst().adjudicatedStatus()).isEqualTo("REVIEW_REQUIRED");
        var notApplicable = ItemRouteRunner.execute(gated(), List.of(message("m1", "内容")), 3, null,
                new ItemRouteRunner.Detector() {
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream) { return result("ERROR"); }
                    @Override public InspectionResult evaluate(DetectionContext context, List<ConversationMessage> inputs,
                                                               InspectionResult upstream, int runIndex) {
                        if (runIndex == 0) return result("HIT");
                        return result("NOT_HIT");
                    }
                }, candidate -> null);
        assertThat(notApplicable.items().getFirst().adjudicatedStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(notApplicable.items().getFirst().terminal().result().getResultStatus()).isEqualTo("NOT_EVALUATED");
    }
}
