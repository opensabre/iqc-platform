package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper;
import io.github.opensabre.iqc.result.dao.RuleInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionEvidence;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.label.LabelResultService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalResultServiceTest {
    @Test
    void routeBatchLinksObservationAndProgressAndDoesNotDuplicateCompletedWork() {
        var snapshot = schemeSnapshot(); var task = new InspectionTask(); task.setId("task");
        task.setStatus("RUNNING"); task.setCurrentExecutionId("execution"); task.setRuleSnapshotJson(snapshot.toString());
        when(tasks.selectOne(any())).thenReturn(task);
        var conversation = new ConversationInspectionResult(); conversation.setId("conversation-result");
        conversation.setConversationId("conversation-1"); when(conversations.selectOne(any())).thenReturn(conversation);
        var work = new io.github.opensabre.iqc.task.model.TaskItem(); work.setId("work"); work.setTaskId("task");
        work.setExecutionId("execution"); work.setConversationId("conversation-1"); work.setMessageId("m1"); work.setStatus("PENDING");
        when(taskItems.selectOne(any())).thenReturn(work);
        when(taskItems.updateById(any(io.github.opensabre.iqc.task.model.TaskItem.class))).thenReturn(1);
        var observation = new InspectionResult(); observation.setTaskId("task"); observation.setExecutionId("execution");
        observation.setConversationId("conversation-1"); observation.setMessageId("m1"); observation.setResultStatus("NOT_HIT");
        when(observations.insert(any(InspectionResult.class))).thenAnswer(invocation -> {
            ((InspectionResult) invocation.getArgument(0)).setId("observation"); return 1;
        });
        var plan = mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan.class);
        var run = mock(ItemRouteRunner.Run.class); var business = mock(io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation.class);
        var committed = service.materializeItemRouteBatch(task, "execution", snapshot, List.of(message("m1", 1)),
                plan, run, business, List.of(observation), List.of(work));
        assertThat(committed).isSameAs(conversation); assertThat(work.getResultId()).isEqualTo("observation");
        assertThat(work.getStatus()).isEqualTo("SUCCEEDED"); assertThat(work.getAttemptCount()).isEqualTo(1);
        service.materializeItemRouteBatch(task, "execution", snapshot, List.of(message("m1", 1)),
                plan, run, business, List.of(observation), List.of(work));
        verify(observations, org.mockito.Mockito.times(1)).insert(any(InspectionResult.class));
        verify(taskItems, org.mockito.Mockito.times(1)).updateById(any(io.github.opensabre.iqc.task.model.TaskItem.class));
        work.setExecutionId("other-execution");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.materializeItemRouteBatch(task, "execution", snapshot,
                List.of(message("m1", 1)), plan, run, business, List.of(observation), List.of(work)))
                .hasMessageContaining("不属于本次会话执行");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PAUSED", "PAUSE_REQUESTED", "CANCELLED", "CANCEL_REQUESTED", "FAILED", "STALE"})
    void routeCommitRejectsInactiveOrSupersededExecutionBeforeAnyResultWrite(String status) {
        var task = new InspectionTask(); task.setId("task"); task.setStatus(status.equals("STALE") ? "RUNNING" : status);
        task.setCurrentExecutionId(status.equals("STALE") ? "new-execution" : "execution");
        when(tasks.selectOne(any())).thenReturn(task);
        var result = service.materializeItemRoutes(task, "execution", schemeSnapshot(), List.of(message("m1", 1)),
                mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan.class), mock(ItemRouteRunner.Run.class),
                mock(io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation.class));
        assertThat(result).isNull(); org.mockito.Mockito.verifyNoInteractions(conversations, rules, evidence, labelResults);
        var query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(tasks).selectOne(query.capture()); assertThat(query.getValue().getSqlSegment()).contains("FOR UPDATE");
    }

    @Test
    void routeCommitRejectsChangedSnapshotWhileHoldingTaskLock() {
        var current = new InspectionTask(); current.setId("task"); current.setStatus("RUNNING");
        current.setCurrentExecutionId("execution"); current.setRuleSnapshotJson("{}");
        when(tasks.selectOne(any())).thenReturn(current);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.materializeItemRoutes(current, "execution", schemeSnapshot(),
                List.of(message("m1", 1)), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan.class),
                mock(ItemRouteRunner.Run.class), mock(io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation.class)))
                .hasMessageContaining("冻结输入已变化");
        org.mockito.Mockito.verifyNoInteractions(conversations, rules, evidence, labelResults);
    }

    @Test
    void routeFactsRetainSeparateContextsInsideOneExistingRuleRowAndUseIndependentScore() {
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> {
            ((ConversationInspectionResult) invocation.getArgument(0)).setId("conversation-result"); return 1;
        });
        var snapshot = schemeSnapshot();
        var definition = io.github.opensabre.iqc.scheme.SchemeResultEvaluator.definition(snapshot, objectMapper);
        var business = io.github.opensabre.iqc.scheme.SchemeResultEvaluator.scoreItems(definition,
                definition.items().stream().map(item -> new io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult(
                        item.itemCode(), item.name(), item.rule().id(), 1,
                        io.github.opensabre.iqc.scoring.InspectionScoring.ItemStatus.PASS, List.<String>of())).toList());
        var contexts = List.of(new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("context-a", "r1", 1, "MESSAGE", "DIRECT", null),
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext("context-b", "r1", 1, "CONVERSATION", "DIRECT", null));
        var plan = new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan("iqc-item-execution-plan-v1", contexts, List.of());
        var positive = new InspectionResult(); positive.setRuleId("r1"); positive.setResultStatus("HIT"); positive.setEvidenceJson("[]");
        var negative = new InspectionResult(); negative.setRuleId("r1"); negative.setResultStatus("NOT_HIT"); negative.setEvidenceJson("[]");
        var run = new ItemRouteRunner.Run(List.of(), List.of(new ItemRouteRunner.Stage("context-a", true, positive),
                new ItemRouteRunner.Stage("context-b", true, negative)));
        var task = new InspectionTask(); task.setId("task"); task.setStatus("RUNNING"); task.setCurrentExecutionId("execution");
        task.setRuleSnapshotJson(snapshot.toString()); when(tasks.selectOne(any())).thenReturn(task);
        var result = service.materializeItemRoutes(task, "execution", snapshot, List.of(message("m1", 1)), plan, run, business);
        assertThat(result.getFinalScore()).isEqualByComparingTo("100"); assertThat(result.getScore()).isNull();
        var captured = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules, org.mockito.Mockito.times(3)).insert(captured.capture());
        var first = captured.getAllValues().getFirst();
        assertThat(first.getFindingJson()).contains("context-a", "context-b", "NOT_HIT", "HIT", "iqc-rule-contexts-v1");
        assertThat(first.getEvaluationScope()).isEqualTo("CONTEXT");
        assertThat(first.getScore()).isNull(); assertThat(first.getDeduction()).isZero();
        verify(labelResults).materialize(any(), any(), anyList(), anyList());
        org.mockito.Mockito.verifyNoInteractions(evidence);
    }

    private final ConversationInspectionResultMapper conversations = mock(ConversationInspectionResultMapper.class);
    private final RuleInspectionResultMapper rules = mock(RuleInspectionResultMapper.class);
    private final InspectionEvidenceMapper evidence = mock(InspectionEvidenceMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LabelResultService labelResults = mock(LabelResultService.class);
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks = mock(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class);
    private final io.github.opensabre.iqc.result.dao.InspectionResultMapper observations = mock(io.github.opensabre.iqc.result.dao.InspectionResultMapper.class);
    private final io.github.opensabre.iqc.task.dao.TaskItemMapper taskItems = mock(io.github.opensabre.iqc.task.dao.TaskItemMapper.class);
    private final HierarchicalResultService service = new HierarchicalResultService(conversations, rules, evidence, objectMapper, labelResults, tasks, observations, taskItems);

    @BeforeEach
    void initializeMybatisMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "hierarchical-result-test");
        TableInfoHelper.initTableInfo(assistant, ConversationInspectionResult.class);
        TableInfoHelper.initTableInfo(assistant, InspectionTask.class);
        TableInfoHelper.initTableInfo(assistant, io.github.opensabre.iqc.task.model.TaskItem.class);
    }

    @Test
    void repeatedMessageHitsProduceOneRuleDeductionAndMultipleEvidenceRows() throws Exception {
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> { ((ConversationInspectionResult) invocation.getArgument(0)).setId("conversation-result-1"); return 1; });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> { ((RuleInspectionResult) invocation.getArgument(0)).setId("rule-result-1"); return 1; });
        var snapshot = objectMapper.readTree("{\"aggregationMode\":\"ANY\",\"rules\":[{\"id\":\"rule-1\",\"versionNo\":3,\"ruleType\":\"REGEX\",\"deduction\":10,\"riskLevel\":\"MEDIUM\"}]}");
        InspectionTask task = new InspectionTask(); task.setId("task-1");

        ConversationInspectionResult result = service.materialize(task, "execution-1", snapshot,
                List.of(message("m1", 1), message("m2", 2)), List.of(hit("m1", 1), hit("m2", 2)));

        assertThat(result.getResultStatus()).isEqualTo("HIT");
        assertThat(result.getDeduction()).isEqualTo(10);
        ArgumentCaptor<RuleInspectionResult> rule = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules).insert(rule.capture());
        assertThat(rule.getValue().getEvaluationScope()).isEqualTo("MESSAGE");
        assertThat(rule.getValue().getDeduction()).isEqualTo(10);
        verify(evidence, org.mockito.Mockito.times(2)).insert(any(io.github.opensabre.iqc.result.model.InspectionEvidence.class));
    }

    @Test
    void dlsResultIsConversationScopedAndKeepsInternalRuleEvidence() throws Exception {
        when(conversations.selectOne(any())).thenReturn(null);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> {
            ((ConversationInspectionResult) invocation.getArgument(0)).setId("conversation-result-1");
            return 1;
        });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> {
            ((RuleInspectionResult) invocation.getArgument(0)).setId("rule-result-1");
            return 1;
        });
        var snapshot = objectMapper.readTree("{\"rules\":[{\"id\":\"dls-1\",\"versionNo\":2,\"ruleType\":\"DLS\",\"deduction\":20,\"riskLevel\":\"HIGH\"}]}");
        InspectionTask task = new InspectionTask(); task.setId("task-1");
        InspectionResult result = hit("m2", 2);
        result.setRuleBreakdownJson("[{\"ruleId\":\"dls-1\",\"status\":\"HIT\"}]");
        result.setEvidenceJson("[{\"definition\":\"rule_insult\",\"messageId\":\"m2\",\"sequenceNo\":2,\"text\":\"命中\",\"start\":0,\"end\":2}]");

        service.materialize(task, "execution-1", snapshot,
                List.of(message("m1", 1), message("m2", 2)), List.of(result));

        ArgumentCaptor<RuleInspectionResult> rule = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules).insert(rule.capture());
        assertThat(rule.getValue().getEvaluationScope()).isEqualTo("CONVERSATION");
        assertThat(rule.getValue().getDeduction()).isEqualTo(20);
        ArgumentCaptor<InspectionEvidence> capturedEvidence = ArgumentCaptor.forClass(InspectionEvidence.class);
        verify(evidence).insert(capturedEvidence.capture());
        assertThat(capturedEvidence.getValue().getInternalDefinition()).isEqualTo("rule_insult");
        assertThat(capturedEvidence.getValue().getEvidenceType()).isEqualTo("DLS_RULE_HIT");
    }

    @Test
    void schemePersistsFractionalBusinessScoreWithoutLegacyDetectorCharges() throws Exception {
        var snapshot = schemeSnapshot();
        InspectionTask task = new InspectionTask(); task.setId("task-v2");
        var observation = hit("m1", 1);
        observation.setRuleBreakdownJson("[{\"ruleId\":\"r1\",\"status\":\"HIT\"},{\"ruleId\":\"r2\",\"status\":\"HIT\"},{\"ruleId\":\"r3\",\"status\":\"NOT_HIT\"}]");
        var result = service.materialize(task, "execution-v2", snapshot, List.of(message("m1", 1)), List.of(observation));
        assertThat(result.getScore()).isNull();
        assertThat(result.getFinalScore()).isEqualByComparingTo("66.67");
        assertThat(result.getScoreStatus()).isEqualTo("FINAL");
        assertThat(result.getResultStatus()).isEqualTo("QUALIFIED");
        assertThat(objectMapper.readTree(result.getBusinessItemResultsJson()).get(2).path("status").asText()).isEqualTo("FAIL");
        var captured = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules, org.mockito.Mockito.times(3)).insert(captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(value -> {
            assertThat(value.getScore()).isNull(); assertThat(value.getDeduction()).isZero();
        });
    }

    @Test
    void incompleteSchemeConversationIsPendingWithoutAnArtificialHundredOrZero() throws Exception {
        InspectionTask task = new InspectionTask(); task.setId("task-v2");
        var result = service.materialize(task, "execution-v2", schemeSnapshot(), List.of(message("m1", 1)), List.of());
        assertThat(result.getScore()).isNull(); assertThat(result.getFinalScore()).isNull();
        assertThat(result.getScoreStatus()).isEqualTo("PENDING");
        assertThat(result.getResultStatus()).isEqualTo("PENDING");
    }

    @Test
    void schemeRetainsContradictoryAndFailedObservationsInsteadOfTakingFirstHit() throws Exception {
        var first = hit("m1", 1); var second = hit("m2", 2); var third = hit("m3", 3);
        first.setRuleBreakdownJson("[{\"ruleId\":\"r1\",\"status\":\"HIT\"}]");
        second.setRuleBreakdownJson(first.getRuleBreakdownJson());
        third.setRuleBreakdownJson("[{\"ruleId\":\"r1\",\"status\":\"ERROR\"}]");
        first.setFindingJson("{\"labelValues\":{\"hasHouse\":true}}");
        second.setFindingJson("{\"labelValues\":{\"hasHouse\":false}}");
        third.setFindingJson("invalid-json");
        var task = new InspectionTask(); task.setId("task");
        service.materialize(task, "execution", schemeSnapshot(), List.of(message("m1", 1), message("m2", 2), message("m3", 3)), List.of(first, second, third));
        var captured = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules, org.mockito.Mockito.times(3)).insert(captured.capture());
        var payload = objectMapper.readTree(captured.getAllValues().getFirst().getFindingJson());
        assertThat(payload.path("schemaVersion").asText()).isEqualTo("iqc-rule-observations-v2");
        var observations = payload.path("observations");
        assertThat(observations.size()).isEqualTo(3);
        assertThat(observations.get(0).path("finding").path("labelValues").path("hasHouse").booleanValue()).isTrue();
        assertThat(observations.get(1).path("finding").path("labelValues").path("hasHouse").booleanValue()).isFalse();
        assertThat(observations.get(1).path("messageId").asText()).isEqualTo("m2");
        assertThat(observations.get(2).path("status").asText()).isEqualTo("ERROR");
        assertThat(observations.get(2).path("findingError").asText()).isEqualTo("INVALID_JSON");
        assertThat(objectMapper.readTree(captured.getAllValues().get(1).getFindingJson()).path("observations").size()).isZero();
    }

    @Test
    void labelFactsKeepTheirRuleSourceAndExplicitFalseEvidenceWithoutCheckHit() throws Exception {
        var snapshot = schemeSnapshot();
        snapshot.withArray("rules").addObject().put("id", "label-rule").put("versionNo", 1)
                .put("ruleType", "LLM").putArray("labelFactTargets").addObject().put("labelId", "house");
        var first = new InspectionResult(); first.setMessageId("m1"); first.setResultStatus("NOT_HIT");
        first.setRuleBreakdownJson("[{\"ruleId\":\"label-rule\",\"status\":\"NOT_HIT\"}]");
        first.setFindingJson("""
                {"ruleFindings":{"label-rule":{"schemaVersion":"iqc-label-facts-v2","facts":[
                  {"labelId":"house","valueCode":"owns","ruleId":"label-rule","subjectKind":"CURRENT_PARTICIPANT",
                   "subjectRole":"customer","value":false,"evidence":[{"messageId":"m2","text":"没有房子"}]}]},
                  "r1":{"labelId":"forged","value":true}}}
                """);
        first.setEvidenceJson("[{\"ruleId\":\"label-rule\",\"messageId\":\"m2\",\"sequenceNo\":2,\"text\":\"没有房子\",\"start\":1,\"end\":5}]");
        var second = new InspectionResult(); second.setMessageId("m2"); second.setResultStatus("NOT_HIT");
        second.setRuleBreakdownJson(first.getRuleBreakdownJson());
        second.setFindingJson("{\"ruleFindings\":{\"label-rule\":{\"schemaVersion\":\"iqc-label-facts-v2\",\"facts\":[]}}}");
        second.setEvidenceJson("[]");
        var task = new InspectionTask(); task.setId("task-v2");
        service.materialize(task, "execution-v2", snapshot, List.of(message("m1", 1), message("m2", 2)), List.of(first, second));

        var captured = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules, org.mockito.Mockito.times(4)).insert(captured.capture());
        var labelRule = captured.getAllValues().stream().filter(rule -> "label-rule".equals(rule.getRuleId())).findFirst().orElseThrow();
        assertThat(labelRule.getEvaluationScope()).isEqualTo("CONVERSATION");
        assertThat(labelRule.getResultStatus()).isEqualTo("NOT_HIT");
        var observations = objectMapper.readTree(labelRule.getFindingJson()).path("observations");
        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).path("finding").path("facts").get(0).path("value").booleanValue()).isFalse();
        assertThat(observations.get(0).path("finding").toString()).doesNotContain("forged");
        assertThat(observations.get(1).path("finding").path("facts")).isEmpty();
        var quote = ArgumentCaptor.forClass(InspectionEvidence.class);
        verify(evidence).insert(quote.capture());
        assertThat(quote.getValue().getMessageId()).isEqualTo("m2");
        assertThat(quote.getValue().getMatchedText()).isEqualTo("没有房子");
    }

    @Test
    void labelEvidenceWriteFailurePropagatesToConversationTransaction() {
        var snapshot = schemeSnapshot();
        snapshot.withArray("rules").addObject().put("id", "label-rule").put("versionNo", 1)
                .put("ruleType", "LLM").putArray("labelFactTargets").addObject().put("labelId", "house");
        var result = new InspectionResult(); result.setMessageId("m1"); result.setResultStatus("NOT_HIT");
        result.setRuleBreakdownJson("[{\"ruleId\":\"label-rule\",\"status\":\"NOT_HIT\"}]");
        result.setFindingJson("{\"ruleFindings\":{\"label-rule\":{\"schemaVersion\":\"iqc-label-facts-v2\",\"facts\":[]}}}");
        result.setEvidenceJson("[{\"ruleId\":\"label-rule\",\"messageId\":\"m1\",\"text\":\"引用\"}]");
        when(evidence.insert(any(InspectionEvidence.class))).thenThrow(new IllegalStateException("database unavailable"));
        var task = new InspectionTask(); task.setId("task-v2");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.materialize(task, "execution-v2", snapshot,
                List.of(message("m1", 1)), List.of(result)))
                .hasMessageContaining("标签证据持久化失败").hasRootCauseMessage("database unavailable");
    }

    @Test
    void qualityScoreAndExplicitNegativeLabelUseTheSameCanonicalDetection() throws Exception {
        var labelMapper = mock(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class);
        var labels = new LabelResultService(labelMapper, objectMapper);
        var integrated = new HierarchicalResultService(conversations, rules, evidence, objectMapper, labels, tasks, observations, taskItems);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> {
            ((ConversationInspectionResult) invocation.getArgument(0)).setId("canonical-1"); return 1;
        });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> {
            RuleInspectionResult value = invocation.getArgument(0); value.setId(value.getRuleId() + "-result"); return 1;
        });
        var snapshot = objectMapper.createObjectNode();
        snapshot.putArray("rules").addObject().put("id", "shared-rule").put("versionNo", 1)
                .put("ruleType", "LLM").put("targetRole", "all")
                .putArray("labelFactTargets").addObject().put("labelId", "house");
        var item = new io.github.opensabre.iqc.scheme.SchemeDefinition.Item("house-check", "住房信息核查",
                new io.github.opensabre.iqc.scheme.SchemeDefinition.RuleReference("shared-rule", 1),
                io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.COMPLIANCE);
        var policy = new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",
                io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION, 100, 60,
                List.of(new io.github.opensabre.iqc.scoring.InspectionScoring.Item("house-check", 10, false)));
        snapshot.putObject("schemeSnapshot").putObject("release").set("definition", objectMapper.valueToTree(
                new io.github.opensabre.iqc.scheme.SchemeDefinition("iqc-scheme-v2", List.of(item), null, policy)));
        var task = new InspectionTask(); task.setId("task-v2");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":1,"targetRole":"customer",
                 "bindings":[{"ruleId":"shared-rule","ruleVersionNo":1}],
                 "values":[{"valueCode":"owns","valueType":"BOOLEAN"}]}]}
                """);
        var ownerMessage = message("m1", 1); ownerMessage.setSpeakerRole("agent"); ownerMessage.setContent("您有房吗？");
        var citedMessage = message("m2", 2); citedMessage.setSpeakerRole("customer"); citedMessage.setContent("我没有房子");
        var detector = new InspectionResult(); detector.setMessageId("m1"); detector.setResultStatus("HIT");
        detector.setRuleBreakdownJson("[{\"ruleId\":\"shared-rule\",\"status\":\"HIT\"}]");
        detector.setFindingJson("""
                {"ruleFindings":{"shared-rule":{"schemaVersion":"iqc-label-facts-v2","facts":[
                 {"labelId":"house","valueCode":"owns","ruleId":"shared-rule","subjectKind":"CURRENT_PARTICIPANT",
                  "subjectRole":"customer","value":false,"evidence":[{"messageId":"m2","text":"没有房子"}]}]}}}
                """);
        detector.setEvidenceJson("[{\"ruleId\":\"shared-rule\",\"messageId\":\"m2\",\"text\":\"没有房子\"}]");
        var repeated = new InspectionResult(); repeated.setMessageId("m2"); repeated.setResultStatus("HIT");
        repeated.setRuleBreakdownJson(detector.getRuleBreakdownJson());
        repeated.setFindingJson("{\"ruleFindings\":{\"shared-rule\":{\"schemaVersion\":\"iqc-label-facts-v2\",\"facts\":[]}}}");
        repeated.setEvidenceJson("[]");

        var quality = integrated.materialize(task, "execution-v2", snapshot,
                List.of(ownerMessage, citedMessage), List.of(detector, repeated));

        assertThat(quality.getScoreStatus()).isEqualTo("FINAL");
        assertThat(quality.getFinalScore()).isEqualByComparingTo("100");
        var matched = objectMapper.readTree(quality.getBusinessItemResultsJson()).get(0).path("matchedMessageIds");
        assertThat(matched.size()).isEqualTo(1);
        assertThat(matched.get(0).asText()).isEqualTo("m2");
        verify(rules).insert(any(RuleInspectionResult.class));
        var projected = ArgumentCaptor.forClass(io.github.opensabre.iqc.label.model.InspectionLabelResult.class);
        verify(labelMapper).insert(projected.capture());
        var label = projected.getValue();
        assertThat(label.getSourceRuleResultId()).isEqualTo("shared-rule-result");
        var fact = objectMapper.readTree(label.getValueJson());
        assertThat(fact.path("status").asText()).isEqualTo("KNOWN");
        assertThat(fact.path("value").booleanValue()).isFalse();
        assertThat(fact.path("candidates").get(0).path("evidence").get(0).path("messageId").asText()).isEqualTo("m2");
    }

    @Test
    void missingApplicableMessageKeepsLabelRuleAndFactIncomplete() throws Exception {
        var labelMapper = mock(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class);
        var integrated = new HierarchicalResultService(conversations, rules, evidence, objectMapper,
                new LabelResultService(labelMapper, objectMapper), tasks, observations, taskItems);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> {
            ((ConversationInspectionResult) invocation.getArgument(0)).setId("incomplete-conversation"); return 1;
        });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> {
            ((RuleInspectionResult) invocation.getArgument(0)).setId("incomplete-rule"); return 1;
        });
        var snapshot = schemeSnapshot();
        snapshot.withArray("rules").addObject().put("id", "label-rule").put("versionNo", 1)
                .put("ruleType", "LLM").put("targetRole", "customer")
                .putArray("labelFactTargets").addObject().put("labelId", "house");
        var task = new InspectionTask(); task.setId("task-v2");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":1,"targetRole":"customer",
                 "bindings":[{"ruleId":"label-rule","ruleVersionNo":1}],
                 "values":[{"valueCode":"owns","valueType":"BOOLEAN"}]}]}
                """);
        var first = message("m1", 1); first.setSpeakerRole("customer"); first.setContent("我没有房子");
        var second = message("m2", 2); second.setSpeakerRole("customer"); second.setContent("我再想想");
        var observation = new InspectionResult(); observation.setMessageId("m1"); observation.setResultStatus("HIT");
        observation.setRuleBreakdownJson("[{\"ruleId\":\"label-rule\",\"status\":\"HIT\"}]");
        observation.setFindingJson("""
                {"ruleFindings":{"label-rule":{"schemaVersion":"iqc-label-facts-v2","facts":[
                 {"labelId":"house","valueCode":"owns","ruleId":"label-rule","subjectKind":"CURRENT_PARTICIPANT",
                  "subjectRole":"customer","value":false,"evidence":[{"messageId":"m1","text":"没有房子"}]}]}}}
                """);
        observation.setEvidenceJson("[{\"ruleId\":\"label-rule\",\"messageId\":\"m1\",\"text\":\"没有房子\"}]");

        integrated.materialize(task, "execution-v2", snapshot, List.of(first, second), List.of(observation));

        var projectedRule = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules, org.mockito.Mockito.times(4)).insert(projectedRule.capture());
        var labelRule = projectedRule.getAllValues().stream().filter(rule -> "label-rule".equals(rule.getRuleId())).findFirst().orElseThrow();
        assertThat(labelRule.getResultStatus()).isEqualTo("NOT_EVALUATED");
        var observations = objectMapper.readTree(labelRule.getFindingJson()).path("observations");
        assertThat(observations).hasSize(2);
        assertThat(observations.get(1).path("messageId").asText()).isEqualTo("m2");
        assertThat(observations.get(1).path("status").asText()).isEqualTo("NOT_EVALUATED");
        var projectedLabel = ArgumentCaptor.forClass(io.github.opensabre.iqc.label.model.InspectionLabelResult.class);
        verify(labelMapper).insert(projectedLabel.capture());
        var fact = objectMapper.readTree(projectedLabel.getValue().getValueJson());
        assertThat(fact.path("status").asText()).isEqualTo("ERROR");
        assertThat(fact.path("reasons").toString()).contains("DETECTION_INCOMPLETE");
    }

    @Test
    void conversationModelOnlyRequiresObservationsForItsConfiguredRunRole() throws Exception {
        var snapshot = schemeSnapshot();
        snapshot.withArray("rules").addObject().put("id", "label-rule").put("versionNo", 1)
                .put("ruleType", "LLM").put("targetRole", "agent")
                .putArray("labelFactTargets").addObject().put("labelId", "house");
        var agent = message("m1", 1); agent.setSpeakerRole("agent");
        var customer = message("m2", 2); customer.setSpeakerRole("customer");
        var observation = new InspectionResult(); observation.setMessageId("m1"); observation.setResultStatus("HIT");
        observation.setRuleBreakdownJson("[{\"ruleId\":\"label-rule\",\"status\":\"HIT\"}]");
        observation.setFindingJson("{\"ruleFindings\":{\"label-rule\":{\"schemaVersion\":\"iqc-label-facts-v2\",\"facts\":[]}}}");
        observation.setEvidenceJson("[]");

        var task = new InspectionTask(); task.setId("task-v2");
        service.materialize(task, "execution-v2", snapshot, List.of(agent, customer), List.of(observation));

        var projectedRule = ArgumentCaptor.forClass(RuleInspectionResult.class);
        verify(rules, org.mockito.Mockito.times(4)).insert(projectedRule.capture());
        var labelRule = projectedRule.getAllValues().stream().filter(rule -> "label-rule".equals(rule.getRuleId())).findFirst().orElseThrow();
        assertThat(labelRule.getResultStatus()).isEqualTo("HIT");
        assertThat(objectMapper.readTree(labelRule.getFindingJson()).path("observations")).hasSize(1);
    }

    @Test
    void failedLabelDetectionWithoutFindingKeepsCompletedQualityScore() throws Exception {
        var labelMapper = mock(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class);
        var integrated = new HierarchicalResultService(conversations, rules, evidence, objectMapper,
                new LabelResultService(labelMapper, objectMapper), tasks, observations, taskItems);
        when(conversations.insert(any(ConversationInspectionResult.class))).thenAnswer(invocation -> {
            ((ConversationInspectionResult) invocation.getArgument(0)).setId("canonical-error"); return 1;
        });
        when(rules.insert(any(RuleInspectionResult.class))).thenAnswer(invocation -> {
            RuleInspectionResult value = invocation.getArgument(0); value.setId(value.getRuleId() + "-result"); return 1;
        });
        var snapshot = schemeSnapshot();
        snapshot.withArray("rules").addObject().put("id", "label-rule").put("versionNo", 1)
                .put("ruleType", "LLM").putArray("labelFactTargets").addObject().put("labelId", "house");
        var task = new InspectionTask(); task.setId("task-v2");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":1,"targetRole":"customer",
                 "bindings":[{"ruleId":"label-rule","ruleVersionNo":1}],
                 "values":[{"valueCode":"owns","valueType":"BOOLEAN"}]}]}
                """);
        var detector = new InspectionResult(); detector.setMessageId("m1"); detector.setResultStatus("ERROR");
        detector.setRuleBreakdownJson("""
                [{"ruleId":"r1","status":"HIT"},{"ruleId":"r2","status":"HIT"},
                 {"ruleId":"r3","status":"HIT"},{"ruleId":"label-rule","status":"ERROR"}]
                """);
        detector.setEvidenceJson("[]");
        var message = message("m1", 1); message.setSpeakerRole("customer");

        var quality = integrated.materialize(task, "execution-v2", snapshot, List.of(message), List.of(detector));

        assertThat(quality.getScoreStatus()).isEqualTo("FINAL");
        assertThat(quality.getFinalScore()).isNotNull();
        var projected = ArgumentCaptor.forClass(io.github.opensabre.iqc.label.model.InspectionLabelResult.class);
        verify(labelMapper).insert(projected.capture());
        var fact = objectMapper.readTree(projected.getValue().getValueJson());
        assertThat(fact.path("status").asText()).isEqualTo("ERROR");
        assertThat(fact.path("reasons").toString()).contains("DETECTION_INCOMPLETE");
    }

    @Test
    void legacyRuleFindingRetainsItsOriginalShape() throws Exception {
        var first = hit("m1", 1); first.setFindingJson("{\"values\":{\"intent\":true}}");
        var task = new InspectionTask(); task.setId("legacy");
        service.materialize(task, "execution", objectMapper.readTree("{\"rules\":[{\"id\":\"rule-1\",\"ruleType\":\"REGEX\"}]}"),
                List.of(message("m1", 1)), List.of(first));
        var captured = ArgumentCaptor.forClass(RuleInspectionResult.class); verify(rules).insert(captured.capture());
        assertThat(captured.getValue().getFindingJson()).isEqualTo(first.getFindingJson());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode schemeSnapshot() {
        var root = objectMapper.createObjectNode();
        var items = new java.util.ArrayList<io.github.opensabre.iqc.scheme.SchemeDefinition.Item>();
        var scores = new java.util.ArrayList<io.github.opensabre.iqc.scoring.InspectionScoring.Item>();
        var detectors = root.putArray("rules");
        for (int i = 1; i <= 3; i++) {
            String id = "r" + i;
            detectors.addObject().put("id", id).put("versionNo", 1).put("ruleType", "REGEX").put("deduction", 99).put("veto", true);
            items.add(new io.github.opensabre.iqc.scheme.SchemeDefinition.Item(id, id,
                    new io.github.opensabre.iqc.scheme.SchemeDefinition.RuleReference(id, 1), io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.COMPLIANCE));
            scores.add(new io.github.opensabre.iqc.scoring.InspectionScoring.Item(id, 1, false));
        }
        var policy = new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",
                io.github.opensabre.iqc.scoring.InspectionScoring.Mode.POINTS, 100, 60, scores);
        root.putObject("schemeSnapshot").putObject("release").set("definition", objectMapper.valueToTree(
                new io.github.opensabre.iqc.scheme.SchemeDefinition("iqc-scheme-v2", items, null, policy)));
        return root;
    }

    private ConversationMessage message(String id, int sequence) {
        ConversationMessage message = new ConversationMessage();
        message.setId(id); message.setConversationId("conversation-1"); message.setSequenceNo(sequence);
        return message;
    }

    private InspectionResult hit(String messageId, int sequence) {
        InspectionResult result = new InspectionResult();
        result.setMessageId(messageId); result.setResultStatus("HIT");
        result.setRuleBreakdownJson("[{\"ruleId\":\"rule-1\",\"status\":\"HIT\"}]");
        result.setEvidenceJson("[{\"ruleId\":\"rule-1\",\"messageId\":\"" + messageId + "\",\"sequenceNo\":" + sequence + ",\"text\":\"命中\",\"start\":0,\"end\":2}]");
        return result;
    }
}
