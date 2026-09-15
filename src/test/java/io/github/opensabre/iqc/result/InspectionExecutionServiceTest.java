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
            objectMapper, mock(TaskExecutionMapper.class), mock(TaskItemMapper.class), mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class));

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
                executions, items, mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class));

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
                executions, items, mock(IqcDataScope.class), llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class));

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
                mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class));

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
                executions, items, scope, llmProvider, mock(UsageCounterRecorder.class), mock(HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class));

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
