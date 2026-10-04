package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.rule.dls.DlsRuleDocument;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.task.model.TaskItem;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;

class InspectionExecutionServiceTest {
    @Test
    void frozenConversationPipelineChecksProjectionThenDetectsScoresAndCallsMaterialization() {
        var definition = new SchemeDefinition(SchemeDefinition.ROUTED_TASK_SCHEMA,
                List.of(new SchemeDefinition.Item("check", "检查", new SchemeDefinition.RuleReference("local", 1),
                        SchemeDefinition.HitMeaning.VIOLATION, null, null,
                        new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null))), null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new InspectionScoring.Item("check", 10, false))));
        var local = (com.fasterxml.jackson.databind.node.ObjectNode) rule("local", "KEYWORD", "优惠", "all");
        local.put("versionNo", 1).put("status", "PUBLISHED").put("deduction", 0).put("veto", false);
        List<JsonNode> frozen = List.of(local);
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan = ReflectionTestUtils.invokeMethod(
                io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class, "compileRoutes", definition, frozen, null, objectMapper);
        var snapshot = objectMapper.createObjectNode(); snapshot.set("rules", objectMapper.valueToTree(frozen));
        var marker = snapshot.putObject("schemeSnapshot"); var release = marker.putObject("release");
        release.set("definition", objectMapper.valueToTree(definition));
        release.set("dependencies", objectMapper.valueToTree(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.Dependencies(
                frozen, null, "RULE_ONLY", null, plan)));
        marker.put("contentHash", io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(release.toString()));
        var task = task(); task.setAgentSnapshotJson(null);
        service.materializeItemRouteConversation(task, "execution", snapshot, List.of(message("customer", "今天优惠")));
        var hierarchy = (HierarchicalResultService) ReflectionTestUtils.getField(service, "hierarchicalResultService");
        var business = org.mockito.ArgumentCaptor.forClass(io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation.class);
        verify(hierarchy).materializeItemRoutes(org.mockito.ArgumentMatchers.eq(task), org.mockito.ArgumentMatchers.eq("execution"),
                org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(plan), any(), business.capture());
        assertThat(business.getValue().scoring().finalScore()).isEqualByComparingTo("90");
        verifyNoInteractions(llmProvider);
        task.setStatus("RUNNING"); task.setCurrentExecutionId("execution"); task.setRuleSnapshotJson(snapshot.toString());
        var tasks = (InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var messages = (ConversationMessageMapper) ReflectionTestUtils.getField(service, "messageMapper");
        var source = message("customer", "今天优惠");
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(messages.selectById(source.getId())).thenReturn(source);
        when(messages.selectList(any())).thenReturn(List.of(source));
        var work = taskItem("work", source.getId(), source.getConversationId(), 1); work.setExecutionId("execution");
        when(hierarchy.materializeItemRouteBatch(any(), any(), any(), org.mockito.ArgumentMatchers.anyList(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new io.github.opensabre.iqc.result.model.ConversationInspectionResult());
        ReflectionTestUtils.invokeMethod(service, "processConversation", task, "execution", snapshot, List.of(work));
        var observations = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(hierarchy).materializeItemRouteBatch(org.mockito.ArgumentMatchers.eq(task), org.mockito.ArgumentMatchers.eq("execution"),
                org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(plan), any(),
                any(), observations.capture(), org.mockito.ArgumentMatchers.eq(List.of(work)));
        var observation = (InspectionResult) observations.getValue().getFirst();
        assertThat(observation.getScore()).isNull(); assertThat(observation.getDeduction()).isZero();
        assertThat(observation.getFindingJson()).contains("projectionOnly", "iqc-item-route-observation-v1");
        assertThat(observation.getExecutionId()).isEqualTo("execution");
        var usage = (UsageCounterRecorder) ReflectionTestUtils.getField(service, "usageCounterRecorder");
        var records = org.mockito.ArgumentCaptor.forClass(io.github.opensabre.governance.usage.UsageRecord.class);
        verify(usage, times(2)).record(records.capture());
        assertThat(records.getAllValues()).extracting(io.github.opensabre.governance.usage.UsageRecord::recordId)
                .containsExactly("inspection-message:" + task.getId() + ":" + source.getId() + ":attempt",
                        "inspection-message:" + task.getId() + ":" + source.getId() + ":success");
        work.setStatus("SUCCEEDED");
        ReflectionTestUtils.invokeMethod(service, "processConversation", task, "execution", snapshot, List.of(work));
        org.mockito.Mockito.verifyNoMoreInteractions(usage);
        task.setStatus("PAUSED");
        ReflectionTestUtils.invokeMethod(service, "processConversation", task, "execution", snapshot, List.of(work));
        task.setRunCount(6);
        task.setStatus("CREATED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.queueSystem(task.getId()))
                .hasMessageContaining("逐项裁决轮数必须为 1 到 5");
        task.setStatus("QUEUED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.run(task.getId(), "execution"))
                .hasMessageContaining("逐项裁决轮数必须为 1 到 5");
        var executions = (TaskExecutionMapper) ReflectionTestUtils.getField(service, "executionMapper");
        var taskItems = (TaskItemMapper) ReflectionTestUtils.getField(service, "taskItemMapper");
        verifyNoInteractions(executions, taskItems);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.materializeItemRouteConversation(task, "execution", snapshot,
                List.of(message("customer", "优惠")))).hasMessageContaining("逐项裁决轮数必须为 1 到 5");
        task.setRunCount(1); local.put("expression", "修改后");
        ((com.fasterxml.jackson.databind.node.ArrayNode) snapshot.path("rules")).set(0, local);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.materializeItemRouteConversation(task, "execution", snapshot,
                List.of(message("customer", "优惠")))).hasMessageContaining("冻结方案不一致");
        org.mockito.Mockito.verifyNoMoreInteractions(hierarchy);
    }

    @Test
    void routeScoringConsumesDistinctItemTerminalsNotSharedRuleIdOrIntermediate() {
        var reference = new SchemeDefinition.RuleReference("semantic", 1);
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("first", "第一项", reference, SchemeDefinition.HitMeaning.VIOLATION),
                        new SchemeDefinition.Item("second", "第二项", reference, SchemeDefinition.HitMeaning.VIOLATION)), null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new InspectionScoring.Item("first", 10, false), new InspectionScoring.Item("second", 20, false))));
        var plan = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan("iqc-item-execution-plan-v1",
                List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("a", "semantic", 1, "MESSAGE", "DIRECT", null),
                        new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("b", "semantic", 1, "CONVERSATION", "DIRECT", null)),
                List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute("first", SchemeDefinition.Route.LLM_ONLY, null, "a"),
                        new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute("second", SchemeDefinition.Route.LLM_ONLY, null, "b")));
        var pass = new InspectionResult(); pass.setRuleId("semantic"); pass.setResultStatus("NOT_HIT");
        var fail = new InspectionResult(); fail.setRuleId("semantic"); fail.setResultStatus("HIT"); fail.setDeduction(99);
        fail.setEvidenceJson("[]");
        var a = new ItemRouteRunner.Stage("a", true, pass); var b = new ItemRouteRunner.Stage("b", true, fail);
        var run = new ItemRouteRunner.Run(List.of(new ItemRouteRunner.Item("first", null, a),
                new ItemRouteRunner.Item("second", null, b)), List.of(a, b));
        var frozen = objectMapper.createObjectNode().put("id", "semantic").put("versionNo", 1).put("targetRole", "customer");
        List<JsonNode> frozenRules = List.of(frozen);
        var scored = service.scoreItemRoutes(definition, plan, run, List.of(message("customer", "内容")), frozenRules);
        assertThat(scored.items()).extracting(io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult::status)
                .containsExactly(InspectionScoring.ItemStatus.PASS, InspectionScoring.ItemStatus.FAIL);
        assertThat(scored.scoring().finalScore()).isEqualByComparingTo("80");
        assertThat(scored.items().get(1).matchedMessageIds()).isEmpty();
        var missing = service.scoreItemRoutes(definition, plan, new ItemRouteRunner.Run(List.of(run.items().getFirst()), List.of(a)),
                List.of(message("customer", "内容")), frozenRules);
        assertThat(missing.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.PENDING);
        assertThat(missing.scoring().finalScore()).isNull();
        var wrong = new ItemRouteRunner.Run(List.of(new ItemRouteRunner.Item("first", null, b)), List.of(b));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.scoreItemRoutes(definition, plan, wrong,
                List.of(message("customer", "内容")), frozenRules)).hasMessageContaining("其他上下文");
        var notApplicable = service.scoreItemRoutes(definition, plan, run, List.of(message("agent", "内容")), frozenRules);
        assertThat(notApplicable.items()).extracting(io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult::status)
                .containsOnly(InspectionScoring.ItemStatus.NOT_APPLICABLE);
        assertThat(notApplicable.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.NOT_APPLICABLE);
        assertThat(notApplicable.scoring().finalScore()).isNull();
        var empty = service.scoreItemRoutes(definition, plan, run, List.of(), frozenRules);
        assertThat(empty.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.PENDING);
        frozen.put("versionNo", 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.scoreItemRoutes(definition, plan, run,
                List.of(message("customer", "内容")), frozenRules)).hasMessageContaining("冻结规则版本");
    }

    @Test
    void conditionalRouteUsesFrozenApplicabilityBeforeScoring() {
        var finalReference = new SchemeDefinition.RuleReference("final", 1);
        var gateReference = new SchemeDefinition.RuleReference("gate", 1);
        var definition = new SchemeDefinition(SchemeDefinition.ROUTED_TASK_SCHEMA,
                List.of(new SchemeDefinition.Item("item", "条件质检项", finalReference, SchemeDefinition.HitMeaning.VIOLATION,
                        gateReference, null, new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null))), null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new InspectionScoring.Item("item", 10, false))));
        var gate = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext(
                "gate-context", "gate", 1, "MESSAGE", "APPLICABILITY", null);
        var terminal = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext(
                "final-context", "final", 1, "MESSAGE", "DIRECT", null);
        var plan = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan("iqc-item-execution-plan-v2",
                List.of(gate, terminal), List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute(
                        "item", SchemeDefinition.Route.RULE_ONLY, null, "final-context", "gate-context")));
        List<JsonNode> frozen = List.of(objectMapper.createObjectNode().put("id", "gate").put("versionNo", 1).put("targetRole", "customer"),
                objectMapper.createObjectNode().put("id", "final").put("versionNo", 1).put("targetRole", "customer"));
        var source = List.of(message("customer", "内容"));

        var notApplicableRun = ItemRouteRunner.execute(plan, source, (context, inputs, upstream) -> {
            var result = new InspectionResult();
            result.setResultStatus("NOT_HIT"); result.setRuleId(context.ruleId()); result.setEvidenceJson("[]");
            return result;
        }, candidate -> null);
        var notApplicable = service.scoreItemRoutes(definition, plan, notApplicableRun, source, frozen);
        assertThat(notApplicable.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.NOT_APPLICABLE);
        assertThat(notApplicable.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.NOT_APPLICABLE);
        assertThat(notApplicable.scoring().finalScore()).isNull();

        var failedRun = ItemRouteRunner.execute(plan, source, (context, inputs, upstream) -> {
            var result = new InspectionResult();
            result.setResultStatus("HIT"); result.setRuleId(context.ruleId()); result.setEvidenceJson("[]");
            return result;
        }, candidate -> null);
        var failed = service.scoreItemRoutes(definition, plan, failedRun, source, frozen);
        assertThat(failed.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.FAIL);
        assertThat(failed.scoring().finalScore()).isEqualByComparingTo("90");
    }

    @Test
    void itemBridgeRunsIndependentConversationLlmAfterPrefilterMiss() {
        var contexts = List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("pre", "local", 1, "MESSAGE", "PREFILTER", null),
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("review", "semantic", 1, "CONVERSATION", "REVIEW", "pre"),
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("direct", "semantic", 1, "CONVERSATION", "DIRECT", null));
        var plan = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan("iqc-item-execution-plan-v1", contexts,
                List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute("violation", SchemeDefinition.Route.RULE_THEN_LLM, "pre", "review"),
                        new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute("intention", SchemeDefinition.Route.LLM_ONLY, null, "direct")));
        when(llmProvider.evaluateConversation(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(String.class)))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "有意向"));
        var run = service.evaluateItemRoutes(task(), plan,
                List.of(rule("local", "KEYWORD", "不存在", "all"), rule("semantic", "LLM", "意向", "all")),
                List.of(message("customer", "想贷款")));
        assertThat(run.items()).extracting(item -> item.terminal().result().getResultStatus()).containsExactly("NOT_HIT", "HIT");
        assertThat(run.items().get(1).terminal().result().getDeduction()).isZero();
        verify(llmProvider, times(1)).evaluateConversation(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(String.class));
    }

    @Test
    void itemBridgeUsesCandidateQuotesRatherThanFullInputForKeywordVerification() {
        var input = message("customer", "前文优惠，真正想贷款，后文");
        var quote = objectMapper.createObjectNode().put("messageId", input.getId()).put("conversationId", input.getConversationId())
                .put("speakerRole", input.getSpeakerRole()).put("text", "想贷款");
        var output = objectMapper.createObjectNode().put("schemaVersion", ItemCandidateOutput.SCHEMA).put("outcome", "MATCH");
        output.putArray("quotes").add(quote);
        when(llmProvider.evaluateCandidates(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(String.class)))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "候选", output.toString()));
        var plan = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan("iqc-item-execution-plan-v1",
                List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("candidate", "semantic", 1, "CONVERSATION", "CANDIDATE", null),
                        new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("verify", "local", 1, "MESSAGE", "VERIFY", "candidate")),
                List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute("check", SchemeDefinition.Route.LLM_THEN_RULE, "candidate", "verify")));
        var semantic = (com.fasterxml.jackson.databind.node.ObjectNode) rule("semantic", "LLM", "意向", "all");
        var local = (com.fasterxml.jackson.databind.node.ObjectNode) rule("local", "KEYWORD", "优惠", "all");
        semantic.put("versionNo", 1); local.put("versionNo", 1);
        List<JsonNode> frozen = List.of(semantic, local);
        var definition = new SchemeDefinition(SchemeDefinition.ROUTED_TASK_SCHEMA,
                List.of(new SchemeDefinition.Item("check", "检查", new SchemeDefinition.RuleReference("local", 1),
                        SchemeDefinition.HitMeaning.VIOLATION, null, null,
                        new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_THEN_RULE, null,
                                new SchemeDefinition.RuleReference("semantic", 1), null, null))), null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new InspectionScoring.Item("check", 10, false))));
        var run = service.evaluateItemRoutes(task(), plan, frozen, List.of(input));
        assertThat(run.items().getFirst().intermediate().result().getResultStatus()).isEqualTo("HIT");
        assertThat(run.items().getFirst().terminal().result().getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(run.items().getFirst().terminal().executed()).isTrue();
        assertThat(input.getContent()).contains("优惠");
        var passed = service.scoreItemRoutes(definition, plan, run, List.of(input), frozen);
        assertThat(passed.scoring().finalScore()).isEqualByComparingTo("100");
        assertThat(passed.items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.PASS);

        // A source quote that contains the keyword is a terminal violation, not a candidate-only hit.
        quote.put("text", "优惠");
        when(llmProvider.evaluateCandidates(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(String.class)))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "候选", output.toString()));
        var matched = service.evaluateItemRoutes(task(), plan, frozen, List.of(input));
        var failed = service.scoreItemRoutes(definition, plan, matched, List.of(input), frozen);
        assertThat(failed.scoring().finalScore()).isEqualByComparingTo("90");
        assertThat(failed.items().getFirst().matchedMessageIds()).containsExactly(input.getId());

        // Fabricated candidate text must stay pending even though the full source contains the keyword.
        quote.put("text", "不存在的优惠承诺");
        when(llmProvider.evaluateCandidates(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(String.class)))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "候选", output.toString()));
        var invalid = service.evaluateItemRoutes(task(), plan, frozen, List.of(input));
        var pending = service.scoreItemRoutes(definition, plan, invalid, List.of(input), frozen);
        assertThat(pending.scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.PENDING);
        assertThat(pending.scoring().finalScore()).isNull();
        assertThat(pending.items().getFirst().matchedMessageIds()).isEmpty();
    }

    @Test
    void conversationReviewCachesOnlyIdenticalPrefilterContextAndPassesFindings() throws Exception {
        var scoped = (com.fasterxml.jackson.databind.node.ObjectNode) rule("review", "LLM", "排除引用", "all");
        scoped.put("inspectionScope", "CONVERSATION");
        var first = message("customer", "第一个候选"); first.setId("m1");
        var second = message("customer", "第二个候选"); second.setId("m2");
        var a = objectMapper.readTree("[{\"ruleId\":\"prefilter\",\"status\":\"HIT\",\"evidence\":\"第一个候选\"}]");
        var b = objectMapper.readTree("[{\"ruleId\":\"prefilter\",\"status\":\"HIT\",\"evidence\":\"第二个候选\"}]");
        var cache = new java.util.LinkedHashMap<String, List<LlmQualityProvider.LlmEvaluation>>();
        var owners = new java.util.LinkedHashMap<String, String>();
        when(llmProvider.evaluateConversation(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(), any(String.class)))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, false, "引用，不违规"));
        var task = task();
        InspectionResult firstResult = ReflectionTestUtils.invokeMethod(service, "evaluateSingle", task, first, scoped, a,
                List.of(first, second), cache, owners, 0);
        ReflectionTestUtils.invokeMethod(service, "evaluateSingle", task, second, scoped, a, List.of(first, second), cache, owners, 0);
        ReflectionTestUtils.invokeMethod(service, "evaluateSingle", task, second, scoped, b, List.of(first, second), cache, owners, 0);
        assertThat(firstResult.getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(firstResult.getEvidence()).isNull();
        assertThat(cache).hasSize(2);
        verify(llmProvider, times(2)).evaluateConversation(org.mockito.ArgumentMatchers.eq(List.of(first, second)),
                org.mockito.ArgumentMatchers.eq(scoped), any(), any(), any(String.class));
        verify(llmProvider, org.mockito.Mockito.never()).evaluateConversation(org.mockito.ArgumentMatchers.anyList(), any(), any(), any(String.class));
    }
    @Test
    void taskIndependentStrategyRunsLlmEvenWhenLocalChecksMissAndAgentSaysRuleOnly() throws Exception {
        InspectionTask task = task();
        task.setAgentSnapshotJson("{\"configJson\":{\"mode\":\"RULE_ONLY\"}}");
        var snapshot = objectMapper.createObjectNode();
        snapshot.putObject("executionStrategy").put("schemaVersion", "1.0").put("mode", "INDEPENDENT");
        snapshot.putArray("rules").add(rule("local", "KEYWORD", "绝不存在", "all")).add(rule("semantic", "LLM", "识别意向", "all"));
        task.setRuleSnapshotJson(snapshot.toString());
        when(llmProvider.evaluate(any(), any(), any(), any(String.class)))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "明确表达意向"));
        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task, message("agent", "今天有优惠"), snapshot);
        assertThat(result.getRuleBreakdownJson()).contains("semantic", "HIT");
        verify(llmProvider).evaluate(any(), any(), any(), any(String.class));
    }

    @Test
    void taskRuleOnlyStrategyExecutesWithoutAgent() {
        InspectionTask task = task(); task.setAgentSnapshotJson(null);
        var snapshot = objectMapper.createObjectNode();
        snapshot.putObject("executionStrategy").put("schemaVersion", "1.0").put("mode", "RULE_ONLY");
        snapshot.putArray("rules").add(rule("local", "KEYWORD", "优惠", "all"));
        task.setRuleSnapshotJson(snapshot.toString());
        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task, message("agent", "今天有优惠"), snapshot);
        assertThat(result.getResultStatus()).isEqualTo("HIT");
        verifyNoInteractions(llmProvider);
    }

    @Test
    void explicitDeterministicHitMappingProducesFalseFactWithOriginalQuote() throws Exception {
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) rule("r-house-no", "REGEX", "没有房子", "customer");
        rule.putArray("labelFactTargets").addObject().put("labelId", "house").put("valueCode", "owns")
                .put("subjectRole", "customer").put("onRuleHitValue", false);
        var matched = evaluate(rule, message("customer", "我没有房子"));
        assertThat(matched.getResultStatus()).isEqualTo("HIT");
        var fact = objectMapper.readTree(matched.getFindingJson()).path("facts").get(0);
        assertThat(fact.path("value").booleanValue()).isFalse();
        assertThat(fact.path("evidence").get(0).path("text").asText()).isEqualTo("没有房子");
        assertThat(objectMapper.readTree(matched.getEvidenceJson()).get(0).path("messageId").asText()).isEqualTo("message-1");
        assertThat(objectMapper.readTree(evaluate(rule, message("customer", "我考虑一下")).getFindingJson()).path("facts")).isEmpty();
        verifyNoInteractions(llmProvider);
    }

    @Test
    void deterministicEmptyRegexHitIsAnExtractionErrorNotAnUnknownFact() throws Exception {
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) rule("r-empty", "REGEX", "^", "customer");
        rule.putArray("labelFactTargets").addObject().put("labelId", "house").put("valueCode", "owns")
                .put("subjectRole", "customer").put("onRuleHitValue", true);
        var result = evaluate(rule, message("customer", "我有房子"));
        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(objectMapper.readTree(result.getFindingJson()).path("factError").asText()).isEqualTo("NO_LOCATED_EVIDENCE");
        assertThat(objectMapper.readTree(result.getEvidenceJson())).isEmpty();
    }

    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-execution-test"), TaskItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-task-test"), InspectionTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-message-test"), ConversationMessage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-result-test"), InspectionResult.class);
    }
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmQualityProvider llmProvider = mock(LlmQualityProvider.class);
    private final InspectionExecutionService service = new InspectionExecutionService(
            mock(InspectionTaskMapper.class), mock(ConversationMapper.class), mock(ConversationMessageMapper.class), mock(InspectionResultMapper.class),
            objectMapper, mock(TaskExecutionMapper.class), mock(TaskItemMapper.class), mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));

    @Test
    void keywordRuleOnlyAppliesToItsTargetSpeaker() {
        InspectionResult hit = evaluate(rule("r-1", "KEYWORD", "优惠", "agent"), message("agent", "今天有优惠"));
        InspectionResult skipped = evaluate(rule("r-1", "KEYWORD", "优惠", "user"), message("agent", "今天有优惠"));

        assertThat(hit.getResultStatus()).isEqualTo("HIT");
        assertThat(hit.getScore()).isEqualTo(90);
        assertThat(skipped.getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(skipped.getReason()).contains("不适用");
    }

    @Test
    void archivedChineseSpeakerRoleIsCanonicalizedBeforeRuleExecutionWithoutChangingRawLine() {
        var tasks = mock(InspectionTaskMapper.class);
        var messages = mock(ConversationMessageMapper.class);
        var results = mock(InspectionResultMapper.class);
        var hierarchy = mock(HierarchicalResultService.class);
        var execution = new InspectionExecutionService(tasks, mock(ConversationMapper.class), messages, results,
                objectMapper, mock(TaskExecutionMapper.class), mock(TaskItemMapper.class), mock(IqcDataScope.class),
                llmProvider, mock(UsageCounterRecorder.class), hierarchy,
                mock(io.github.opensabre.iqc.label.LabelCandidateService.class),
                mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));
        var task = task();
        task.setStatus("RUNNING");
        var archived = message("客户", "我没有房子");
        archived.setRawLine("客户：我没有房子");
        var item = taskItem("item-1", archived.getId(), archived.getConversationId(), 1);
        var snapshot = objectMapper.createArrayNode().add(rule("house", "REGEX", "没有房子", "user"));
        task.setRuleSnapshotJson(snapshot.toString());
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(messages.selectById(archived.getId())).thenReturn(archived);

        ReflectionTestUtils.invokeMethod(execution, "processConversation", task, "execution-1", snapshot, List.of(item));

        var captured = org.mockito.ArgumentCaptor.forClass(InspectionResult.class);
        verify(results).insert(captured.capture());
        assertThat(captured.getValue().getResultStatus()).isEqualTo("HIT");
        assertThat(captured.getValue().getSpeakerRole()).isEqualTo("user");
        assertThat(archived.getRawLine()).isEqualTo("客户：我没有房子");
    }

    @Test
    void labelOnlyConversationExecutesFrozenDetectorAndHandsUnscoredFactsToMaterialization() throws Exception {
        var tasks = mock(InspectionTaskMapper.class);
        var messages = mock(ConversationMessageMapper.class);
        var observations = mock(InspectionResultMapper.class);
        var hierarchy = mock(HierarchicalResultService.class);
        var execution = new InspectionExecutionService(tasks, mock(ConversationMapper.class), messages, observations,
                objectMapper, mock(TaskExecutionMapper.class), mock(TaskItemMapper.class), mock(IqcDataScope.class),
                llmProvider, mock(UsageCounterRecorder.class), hierarchy,
                mock(io.github.opensabre.iqc.label.LabelCandidateService.class),
                mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));
        var task = task(); task.setStatus("RUNNING"); task.setCurrentExecutionId("execution-1");
        var policy = new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION,
                100, 60, List.of());
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(), null, policy, null,
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("house", 1)));
        var snapshot = objectMapper.createObjectNode();
        snapshot.putObject("executionStrategy").put("schemaVersion", "1.0").put("mode", "RULE_ONLY");
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) rule("r-house-no", "REGEX", "没有房子", "customer");
        rule.put("versionNo", 1);
        rule.putArray("labelFactTargets").addObject().put("labelId", "house").put("valueCode", "owns")
                .put("subjectRole", "customer").put("onRuleHitValue", false);
        snapshot.putArray("rules").add(rule);
        snapshot.putObject("schemeSnapshot").putObject("release").set("definition", objectMapper.valueToTree(definition));
        task.setRuleSnapshotJson(snapshot.toString());
        var customer = message("customer", "我没有房子");
        var item = taskItem("item-1", customer.getId(), customer.getConversationId(), 1);
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(messages.selectById(customer.getId())).thenReturn(customer);
        when(messages.selectList(any())).thenReturn(List.of(customer));
        when(observations.selectList(any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(execution, "processConversation", task, "execution-1", snapshot, List.of(item));

        var captured = org.mockito.ArgumentCaptor.forClass(InspectionResult.class);
        verify(observations).insert(captured.capture());
        var result = captured.getValue();
        assertThat(item.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getScore()).isNull();
        assertThat(result.getDeduction()).isZero();
        var fact = objectMapper.readTree(result.getFindingJson()).path("ruleFindings").path("r-house-no")
                .path("facts").get(0);
        assertThat(fact.path("value").booleanValue()).isFalse();
        assertThat(fact.path("evidence").get(0).path("text").asText()).isEqualTo("没有房子");
        assertThat(objectMapper.readTree(result.getEvidenceJson()).get(0).path("messageId").asText())
                .isEqualTo(customer.getId());
        assertThat(objectMapper.readTree(result.getRuleBreakdownJson()).get(0).path("score").isNull()).isTrue();
        verify(hierarchy).materialize(org.mockito.ArgumentMatchers.eq(task), org.mockito.ArgumentMatchers.eq("execution-1"),
                org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.eq(List.of(customer)),
                org.mockito.ArgumentMatchers.argThat(values -> values.size() == 1 && values.getFirst() == result));
        verifyNoInteractions(llmProvider);
    }

    @Test
    void unsupportedLlmRuleIsDiagnosticError() {
        JsonNode rule = rule("r-llm", "LLM", "识别违规承诺", "all");
        when(llmProvider.evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"), org.mockito.ArgumentMatchers.eq(rule),
                org.mockito.ArgumentMatchers.nullable(JsonNode.class), org.mockito.ArgumentMatchers.eq("inspection-message:task-1:message-1:rule:r-llm")))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(false, false, "LLM 规则未配置可用适配器"));

        InspectionResult result = evaluate(rule, message("agent", "今天有优惠"));

        assertThat(result.getResultStatus()).isEqualTo("ERROR");
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getReason()).contains("未配置可用适配器");
        assertThat(result.getRuleBreakdownJson()).contains("ERROR");
    }

    @Test
    void multiRunStoresConsensusConfidence() throws Exception {
        InspectionTask task = task(); task.setRunCount(3); task.setConfidenceThreshold(new java.math.BigDecimal("0.80"));
        com.fasterxml.jackson.databind.node.ArrayNode snapshot = objectMapper.createArrayNode();
        snapshot.add(rule("r-1", "KEYWORD", "优惠", "agent"));
        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluateWithRuns", task,
                message("agent", "今天有优惠"), snapshot, List.of(message("agent", "今天有优惠")));
        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(objectMapper.readTree(result.getFindingJson()).path("runCount").asInt()).isEqualTo(3);
        assertThat(objectMapper.readTree(result.getFindingJson()).path("runs")).hasSize(3);
        assertThat(objectMapper.readTree(result.getFindingJson()).path("confidence").asDouble()).isEqualTo(1.0);
    }

    @Test
    void conversationFactsRunOncePerConfiguredRunAndRetainFalseEvidence() throws Exception {
        InspectionTask task = task(); task.setRunCount(2); task.setCurrentExecutionId("execution-1");
        var snapshot = objectMapper.createObjectNode();
        snapshot.putObject("executionStrategy").put("schemaVersion", "1.0").put("mode", "INDEPENDENT");
        var rule = objectMapper.createObjectNode().put("id", "label-rule").put("ruleType", "LLM").put("targetRole", "all");
        rule.putArray("labelFactTargets").addObject().put("labelId", "house").put("valueCode", "owns");
        snapshot.putArray("rules").add(rule); task.setRuleSnapshotJson(snapshot.toString());
        var first = message("agent", "您有房吗？"); first.setId("m1"); first.setSequenceNo(1);
        var second = message("customer", "我没有房子"); second.setId("m2"); second.setSequenceNo(2);
        String facts = """
                {"hit":false,"reason":"未违规","schemaVersion":"iqc-label-facts-v2","facts":[
                  {"labelId":"house","valueCode":"owns","ruleId":"label-rule","subjectKind":"CURRENT_PARTICIPANT",
                   "subjectRole":"customer","value":false,"evidence":[{"messageId":"m2","text":"没有房子"}]}]}
                """;
        when(llmProvider.evaluateConversation(any(), any(), org.mockito.ArgumentMatchers.nullable(JsonNode.class), any()))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, false, "未违规", facts));
        var cache = new java.util.LinkedHashMap<String, java.util.List<LlmQualityProvider.LlmEvaluation>>();
        var owners = new java.util.LinkedHashMap<String, String>();

        InspectionResult owner = ReflectionTestUtils.invokeMethod(service, "evaluateWithRuns", task, first, snapshot,
                List.of(first, second), cache, owners);
        InspectionResult repeated = ReflectionTestUtils.invokeMethod(service, "evaluateWithRuns", task, second, snapshot,
                List.of(first, second), cache, owners);

        verify(llmProvider, times(2)).evaluateConversation(any(), org.mockito.ArgumentMatchers.eq(rule),
                org.mockito.ArgumentMatchers.nullable(JsonNode.class), any());
        verify(llmProvider, times(0)).evaluate(any(), any(), any(), any(String.class));
        assertThat(owner.getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(owner.getEvidenceJson()).contains("m2", "没有房子");
        JsonNode ownerFinding = objectMapper.readTree(owner.getFindingJson()).path("ruleFindings").path("label-rule");
        assertThat(ownerFinding.path("facts")).hasSize(1);
        assertThat(ownerFinding.path("facts").get(0).path("value").booleanValue()).isFalse();
        assertThat(objectMapper.readTree(repeated.getFindingJson()).path("ruleFindings").path("label-rule").path("facts")).isEmpty();
        assertThat(repeated.getEvidenceJson()).isEqualTo("[]");
    }

    @Test
    void explicitConversationCheckSharesFullContextWithoutFabricatingMessageEvidence() {
        var task = task(); task.setCurrentExecutionId("scope-execution");
        var snapshot = objectMapper.createObjectNode();
        snapshot.putObject("executionStrategy").put("schemaVersion", "1.0").put("mode", "INDEPENDENT");
        var scopedRule = objectMapper.createObjectNode().put("id", "semantic").put("ruleType", "LLM")
                .put("targetRole", "all").put("inspectionScope", "CONVERSATION");
        snapshot.putArray("rules").add(scopedRule); task.setRuleSnapshotJson(snapshot.toString());
        var first = message("agent", "已告知风险"); first.setId("m1"); first.setSequenceNo(1);
        var second = message("customer", "我理解了"); second.setId("m2"); second.setSequenceNo(2);
        when(llmProvider.evaluateConversation(any(), any(), org.mockito.ArgumentMatchers.nullable(JsonNode.class), any()))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "完整会话满足告知要求"));
        var cache = new java.util.LinkedHashMap<String, java.util.List<LlmQualityProvider.LlmEvaluation>>();
        var owners = new java.util.LinkedHashMap<String, String>();
        for (var message : List.of(first, second)) {
            InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluateWithRuns", task, message,
                    snapshot, List.of(first, second), cache, owners);
            assertThat(result.getResultStatus()).isEqualTo("HIT");
            assertThat(result.getEvidence()).isNull();
            assertThat(result.getEvidenceJson()).isEqualTo("[]");
        }
        verify(llmProvider).evaluateConversation(org.mockito.ArgumentMatchers.eq(List.of(first, second)),
                org.mockito.ArgumentMatchers.eq(scopedRule), org.mockito.ArgumentMatchers.nullable(JsonNode.class), any());
        verify(llmProvider, times(0)).evaluate(any(), any(), any(), any(String.class));
    }

    @Test
    void evenRunTieRequiresHumanReview() {
        InspectionTask task = task(); task.setRunCount(2);
        JsonNode rule = rule("r-llm", "LLM", "判断意图", "all");
        when(llmProvider.evaluate(any(), org.mockito.ArgumentMatchers.eq(rule), org.mockito.ArgumentMatchers.nullable(JsonNode.class), any()))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "命中"), new LlmQualityProvider.LlmEvaluation(true, false, "未命中"));

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluateWithRuns", task,
                message("agent", "测试话术"), objectMapper.createArrayNode().add(rule), List.of(message("agent", "测试话术")));

        assertThat(result.getResultStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.getRuleBreakdownJson()).contains("REVIEW_REQUIRED");
    }

    @Test
    void llmCandidateOnlySelectsRulesBoundToThatLabel() {
        InspectionTask task = task();
        task.setLabelScopeSnapshotJson("{\"labels\":[{\"id\":\"label-a\",\"code\":\"A\",\"bindings\":[{\"ruleId\":\"rule-a\"}]},{\"id\":\"label-b\",\"code\":\"B\",\"bindings\":[{\"ruleId\":\"rule-b\"}]}]}");
        InspectionResult candidate = new InspectionResult();
        candidate.setFindingJson("{\"candidates\":[{\"labelCode\":\"A\"}]}");

        Set<String> ruleIds = ReflectionTestUtils.invokeMethod(service, "candidateRuleIds", task, candidate);

        assertThat(ruleIds).containsExactly("rule-a");
    }

    @Test
    void llmThenRuleKeepsExtractionAndLocalRuleBreakdownAligned() throws Exception {
        InspectionTask task = task();
        task.setAgentSnapshotJson("{\"configJson\":{\"mode\":\"LLM_THEN_RULE\"}}");
        task.setLabelScopeSnapshotJson("{\"labels\":[{\"id\":\"label-a\",\"code\":\"A\",\"bindings\":[{\"ruleId\":\"rule-a\"}]}]}");
        JsonNode localRule = rule("rule-a", "KEYWORD", "优惠", "agent");
        when(llmProvider.evaluate(any(), any(), org.mockito.ArgumentMatchers.nullable(JsonNode.class), any()))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "提取到候选", "{\"candidates\":[{\"labelCode\":\"A\"}]}"));

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task,
                message("agent", "今天有优惠"), objectMapper.createArrayNode().add(localRule), List.of(message("agent", "今天有优惠")));

        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(objectMapper.readTree(result.getRuleBreakdownJson())).hasSize(2);
    }

    @Test
    void mixedRulesExposePartialErrorAndBreakdown() throws Exception {
        JsonNode keyword = rule("r-1", "KEYWORD", "优惠", "all");
        JsonNode llm = rule("r-2", "LLM", "判断语义", "all");
        when(llmProvider.evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"), org.mockito.ArgumentMatchers.eq(llm),
                org.mockito.ArgumentMatchers.nullable(JsonNode.class), org.mockito.ArgumentMatchers.eq("inspection-message:task-1:message-1:rule:r-2")))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(false, false, "LLM 规则未配置可用适配器"));
        JsonNode snapshot = objectMapper.createArrayNode().add(keyword).add(llm);

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task(), message("agent", "今天有优惠"), snapshot);

        assertThat(result).isNotNull();
        assertThat(result.getResultStatus()).isEqualTo("PARTIAL_ERROR");
        assertThat(result.getScore()).isZero();
        assertThat(result.getRuleId()).isEqualTo("r-1,r-2");
        assertThat(objectMapper.readTree(result.getRuleBreakdownJson())).hasSize(2);
    }

    @Test
    void breakdownKeepsRuleIdentityAndScoringDecision() throws Exception {
        JsonNode configuredRule = ((com.fasterxml.jackson.databind.node.ObjectNode) rule("r-1", "KEYWORD", "优惠", "all"))
                .put("name", "违规优惠承诺").put("code", "illegal_discount").put("category", "COMPLIANCE")
                .put("deduction", 25).put("riskLevel", "HIGH").put("veto", true);

        InspectionResult result = evaluate(configuredRule, message("agent", "今天有优惠"));
        JsonNode breakdown = objectMapper.readTree(result.getRuleBreakdownJson()).get(0);

        assertThat(breakdown.path("ruleName").asText()).isEqualTo("违规优惠承诺");
        assertThat(breakdown.path("ruleCode").asText()).isEqualTo("illegal_discount");
        assertThat(breakdown.path("category").asText()).isEqualTo("COMPLIANCE");
        assertThat(breakdown.path("deduction").asInt()).isEqualTo(25);
        assertThat(breakdown.path("veto").asBoolean()).isTrue();
        assertThat(result.getScore()).isZero();
    }

    @Test
    void structuredConditionIsEvaluatedInTaskExecution() {
        JsonNode structured = rule("r-structured", "STRUCTURED",
                "{\"all\":[{\"field\":\"content\",\"operator\":\"contains\",\"value\":\"优惠\"},{\"field\":\"speakerRole\",\"operator\":\"equals\",\"value\":\"agent\"}]}", "all");

        InspectionResult result = evaluate(structured, message("agent", "今天有优惠"));

        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(result.getEvidenceJson()).contains("优惠");
    }

    @Test
    void dlsRuleUsesConversationContextAndAnchorsOneResult() throws Exception {
        DlsRuleDocument document = new DlsRuleDocument("1.0", null, List.of(
                new DlsRuleDocument.Definition("slot_need", "SLOT", "我要投诉", "all"),
                new DlsRuleDocument.Definition("slot_phone", "SLOT", "客服电话", "all"),
                new DlsRuleDocument.Definition("rule_need", "RULE", "[slot_need]", "user"),
                new DlsRuleDocument.Definition("rule_guide", "RULE", "[slot_phone]", "agent")
        ), "引导回电", "[rule_need]%[rule_guide]");
        ConversationMessage customer = message("user", "我要投诉"); customer.setId("customer"); customer.setSequenceNo(1);
        ConversationMessage agent = message("agent", "请拨打客服电话"); agent.setId("agent"); agent.setSequenceNo(2);
        JsonNode dls = rule("r-dls", "DLS", objectMapper.writeValueAsString(document), "all");

        InspectionResult anchored = ReflectionTestUtils.invokeMethod(service, "evaluate", task(), customer,
                objectMapper.createArrayNode().add(dls), List.of(customer, agent));
        InspectionResult secondary = ReflectionTestUtils.invokeMethod(service, "evaluate", task(), agent,
                objectMapper.createArrayNode().add(dls), List.of(customer, agent));

        assertThat(anchored.getResultStatus()).isEqualTo("HIT");
        assertThat(anchored.getEvidenceJson()).contains("rule_need").contains("rule_guide");
        assertThat(secondary.getResultStatus()).isEqualTo("NOT_HIT");
    }

    @Test
    void ruleThenLlmSkipsLlmWhenLocalRulesDoNotHit() {
        JsonNode local = rule("r-local", "KEYWORD", "优惠", "all");
        JsonNode llm = rule("r-llm", "LLM", "判断语义", "all");
        InspectionTask task = task();
        task.setAgentSnapshotJson("{\"configJson\":{\"mode\":\"RULE_THEN_LLM\"}}");

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task,
                message("agent", "您好，欢迎咨询"), objectMapper.createArrayNode().add(local).add(llm));

        assertThat(result.getResultStatus()).isEqualTo("NOT_HIT");
        verify(llmProvider, times(0)).evaluate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(llm), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void schemeDlsAbsenceConditionRemainsAConversationHitWithoutPositiveEvidence() throws Exception {
        DlsRuleDocument document = new DlsRuleDocument("1.0", null, List.of(
                new DlsRuleDocument.Definition("rule_greeting", "RULE", "您好", "agent")), "缺少开场白", "![rule_greeting]");
        var dls = rule("r-dls", "DLS", objectMapper.writeValueAsString(document), "all");
        var task = task(); task.setRuleSnapshotJson("{\"schemeSnapshot\":{},\"executionStrategy\":{\"schemaVersion\":\"1.0\",\"mode\":\"RULE_ONLY\"}}");
        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task, message("agent", "请问有什么需要"),
                objectMapper.createArrayNode().add(dls));
        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(objectMapper.readTree(result.getEvidenceJson())).isEmpty();
    }

    @Test
    void ruleThenLlmPassesLocalFindingsToLlmAfterCandidateHit() {
        JsonNode local = rule("r-local", "KEYWORD", "优惠", "all");
        JsonNode llm = rule("r-llm", "LLM", "判断语义", "all");
        InspectionTask task = task();
        task.setAgentSnapshotJson("{\"configJson\":{\"mode\":\"RULE_THEN_LLM\"}}");
        when(llmProvider.evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"), org.mockito.ArgumentMatchers.eq(llm),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, false, "仅为正常优惠说明"));

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task,
                message("agent", "今天有优惠"), objectMapper.createArrayNode().add(local).add(llm));

        assertThat(result.getResultStatus()).isEqualTo("NOT_HIT");
        verify(llmProvider).evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"), org.mockito.ArgumentMatchers.eq(llm),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.argThat(value -> value.toString().contains("r-local")),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ruleThenLlmUsesAgentReviewWhenOnlyLocalRulesAreConfigured() throws Exception {
        JsonNode local = rule("r-local", "KEYWORD", "优惠", "all");
        InspectionTask task = task();
        task.setAgentId("agent-1");
        task.setAgentSnapshotJson("{\"configJson\":{\"mode\":\"RULE_THEN_LLM\"}}");
        when(llmProvider.evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"),
                org.mockito.ArgumentMatchers.argThat(value -> "agent:agent-1".equals(value.path("id").asText())),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new LlmQualityProvider.LlmEvaluation(true, true, "确认存在违规优惠承诺"));

        InspectionResult result = ReflectionTestUtils.invokeMethod(service, "evaluate", task,
                message("agent", "今天有优惠"), objectMapper.createArrayNode().add(local));

        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(result.getReason()).contains("确认存在违规优惠承诺");
        assertThat(objectMapper.readTree(result.getRuleBreakdownJson())).hasSize(2);
        verify(llmProvider).evaluate(org.mockito.ArgumentMatchers.eq("今天有优惠"),
                org.mockito.ArgumentMatchers.argThat(value -> "agent:agent-1".equals(value.path("id").asText())),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.argThat(value -> value.toString().contains("r-local")),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void configuredConcurrencyProcessesDifferentConversationsInParallel() {
        InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        InspectionResultMapper results = mock(InspectionResultMapper.class);
        TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
        TaskItemMapper items = mock(TaskItemMapper.class);
        InspectionTask task = task(); task.setStatus("QUEUED"); task.setConcurrencyLimit(2);
        task.setProcessedMessages(0); task.setFailedMessages(0); task.setRuleSnapshotJson("[]");
        TaskExecution execution = new TaskExecution(); execution.setId("execution-1");
        TaskItem first = taskItem("item-1", "message-1", "conversation-1", 1);
        TaskItem second = taskItem("item-2", "message-2", "conversation-2", 2);
        when(tasks.selectById("task-1")).thenReturn(task);
        when(tasks.update(isNull(), any())).thenReturn(1);
        when(executions.selectById("execution-1")).thenReturn(execution);
        when(items.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(first, second));
        AtomicInteger active = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
        when(messages.selectById(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try { Thread.sleep(120); } finally { active.decrementAndGet(); }
            ConversationMessage message = message("agent", "正常话术");
            message.setId(invocation.getArgument(0));
            message.setConversationId("message-1".equals(message.getId()) ? "conversation-1" : "conversation-2");
            return message;
        });
        InspectionExecutionService concurrentService = new InspectionExecutionService(tasks, mock(ConversationMapper.class), messages, results, objectMapper,
                executions, items, mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));

        InspectionTask completed = concurrentService.run("task-1", "execution-1");

        assertThat(maximum.get()).isEqualTo(2);
        assertThat(completed.getProcessedMessages()).isEqualTo(2);
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void resumedExecutionDoesNotCountAlreadySucceededItemsTwice() {
        InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        InspectionResultMapper results = mock(InspectionResultMapper.class);
        TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
        TaskItemMapper items = mock(TaskItemMapper.class);
        InspectionTask task = task(); task.setStatus("QUEUED"); task.setConcurrencyLimit(1); task.setTotalMessages(2);
        task.setProcessedMessages(1); task.setRuleSnapshotJson("[]");
        TaskExecution execution = new TaskExecution(); execution.setId("execution-1");
        TaskItem completed = taskItem("item-1", "message-1", "conversation-1", 1); completed.setStatus("SUCCEEDED");
        TaskItem pending = taskItem("item-2", "message-2", "conversation-1", 2); pending.setStatus("PENDING");
        when(tasks.selectById("task-1")).thenReturn(task);
        when(tasks.update(isNull(), any())).thenReturn(1);
        when(executions.selectById("execution-1")).thenReturn(execution);
        when(items.selectList(any())).thenReturn(List.of(completed, pending));
        ConversationMessage first = message("agent", "已完成"); first.setId("message-1");
        ConversationMessage second = message("agent", "待恢复"); second.setId("message-2");
        when(messages.selectById("message-1")).thenReturn(first); when(messages.selectById("message-2")).thenReturn(second);
        InspectionExecutionService resumed = new InspectionExecutionService(tasks, mock(ConversationMapper.class), messages, results, objectMapper,
                executions, items, mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));

        InspectionTask result = resumed.run("task-1", "execution-1");

        assertThat(result.getProcessedMessages()).isEqualTo(2);
        verify(results, times(1)).insert(any(InspectionResult.class));
    }

    @Test
    void secondNodeDoesNotProcessAnExecutionAlreadyClaimedByAnotherNode() {
        InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
        TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
        TaskItemMapper items = mock(TaskItemMapper.class);
        InspectionTask queued = task(); queued.setStatus("QUEUED"); queued.setCurrentExecutionId("execution-1");
        InspectionTask running = task(); running.setStatus("RUNNING"); running.setCurrentExecutionId("execution-1");
        TaskExecution execution = new TaskExecution(); execution.setId("execution-1"); execution.setStatus("QUEUED");
        when(tasks.selectById("task-1")).thenReturn(queued, running);
        when(executions.selectById("execution-1")).thenReturn(execution);
        when(tasks.update(isNull(), any())).thenReturn(0);
        InspectionExecutionService contender = new InspectionExecutionService(tasks, mock(ConversationMapper.class), mock(ConversationMessageMapper.class),
                mock(InspectionResultMapper.class), objectMapper, executions, items, mock(IqcDataScope.class), llmProvider,
                mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));

        assertThat(contender.run("task-1", "execution-1")).isSameAs(running);
        verifyNoInteractions(items);
    }

    @Test
    void resumeCreatesANewExecutionContainingOnlyUnfinishedItems() {
        InspectionTaskMapper tasks = mock(InspectionTaskMapper.class); TaskExecutionMapper executions = mock(TaskExecutionMapper.class); TaskItemMapper items = mock(TaskItemMapper.class); IqcDataScope scope = mock(IqcDataScope.class);
        InspectionTask paused = task(); paused.setStatus("PAUSED"); paused.setCurrentExecutionId("execution-old"); paused.setAttemptCount(1); paused.setTotalMessages(2);
        TaskItem succeeded = taskItem("done", "message-1", "conversation-1", 1); succeeded.setStatus("SUCCEEDED");
        TaskItem pending = taskItem("pending", "message-2", "conversation-1", 2); pending.setStatus("PENDING");
        when(tasks.selectById("task-1")).thenReturn(paused); when(scope.canView(any(), any())).thenReturn(true);
        when(items.selectList(any())).thenReturn(List.of(succeeded, pending)); when(tasks.update(isNull(), any())).thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> { TaskExecution value = invocation.getArgument(0); value.setId("execution-new"); return 1; }).when(executions).insert(any(TaskExecution.class));
        InspectionExecutionService resumed = new InspectionExecutionService(tasks, mock(ConversationMapper.class), mock(ConversationMessageMapper.class), mock(InspectionResultMapper.class), objectMapper,
                executions, items, scope, llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));

        InspectionTask result = resumed.resume("task-1");

        assertThat(result.getCurrentExecutionId()).isEqualTo("execution-new");
        assertThat(result.getProcessedMessages()).isEqualTo(1);
        org.mockito.ArgumentCaptor<TaskItem> copied = org.mockito.ArgumentCaptor.forClass(TaskItem.class);
        verify(items).insert(copied.capture());
        assertThat(copied.getValue().getMessageId()).isEqualTo("message-2");
    }

    private InspectionResult evaluate(JsonNode rule, ConversationMessage message) {
        return ReflectionTestUtils.invokeMethod(service, "evaluateSingle", task(), message, rule);
    }

    private InspectionTask task() {
        InspectionTask task = new InspectionTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        return task;
    }

    private ConversationMessage message(String role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setId("message-1");
        message.setConversationId("conversation-1");
        message.setSequenceNo(1);
        message.setSpeakerRole(role);
        message.setContent(content);
        return message;
    }

    private TaskItem taskItem(String id, String messageId, String conversationId, int sequence) {
        TaskItem item = new TaskItem(); item.setId(id); item.setTaskId("task-1"); item.setExecutionId("execution-1");
        item.setMessageId(messageId); item.setConversationId(conversationId); item.setSequenceNo(sequence); item.setStatus("PENDING"); item.setAttemptCount(0);
        return item;
    }

    private JsonNode rule(String id, String type, String expression, String targetRole) {
        return objectMapper.createObjectNode()
                .put("id", id).put("ruleType", type).put("expression", expression)
                .put("targetRole", targetRole).put("deduction", 10).put("riskLevel", "MEDIUM");
    }
}
