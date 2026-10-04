package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.QualityRuleSetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelResolutionService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.task.model.TaskItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectionTaskServiceTest {
    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-test"), InspectionTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-test"), TaskExecution.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-test"), TaskItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-test"), ConversationMessage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-test"), Conversation.class);
    }

    private final InspectionTaskMapper taskMapper = mock(InspectionTaskMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final QualityAgentMapper agentMapper = mock(QualityAgentMapper.class);
    private final QualityRuleMapper ruleMapper = mock(QualityRuleMapper.class);
    private final QualityRuleSetService ruleSetService = mock(QualityRuleSetService.class);
    private final ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
    private final InspectionResultMapper resultMapper = mock(InspectionResultMapper.class);
    private final TaskExecutionMapper executionMapper = mock(TaskExecutionMapper.class);
    private final TaskItemMapper taskItemMapper = mock(TaskItemMapper.class);
    private final IqcDataScope dataScope = mock(IqcDataScope.class);
    private final LabelResolutionService labelResolution = mock(LabelResolutionService.class);
    private final InspectionExecutionService executionService = new InspectionExecutionService(taskMapper, conversationMapper, messageMapper, resultMapper,
            new ObjectMapper(), executionMapper, taskItemMapper, dataScope, mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class), mock(UsageCounterRecorder.class), mock(io.github.opensabre.iqc.result.HierarchicalResultService.class), mock(io.github.opensabre.iqc.label.LabelCandidateService.class), mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class));
    private final InspectionTaskService taskService = new InspectionTaskService(taskMapper, conversationMapper, agentMapper, ruleMapper, ruleSetService,
            new ObjectMapper(), executionMapper, dataScope, labelResolution);

    @Test
    void retryOnlyQueuesFailedMessagesAndPreservesSuccessfulProgress() {
        InspectionTask task = task("PARTIAL_FAILED");
        task.setTotalMessages(2);
        ConversationMessage successMessage = message("message-1", 1);
        ConversationMessage failedMessage = message("message-2", 2);
        TaskItem successItem = item("message-1", "SUCCEEDED");
        TaskItem failedItem = item("message-2", "FAILED");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(dataScope.canView(null, null)).thenReturn(true);
        when(taskItemMapper.selectList(any())).thenReturn(List.of(successItem, failedItem));
        when(messageMapper.selectList(any())).thenReturn(List.of(successMessage, failedMessage));
        when(taskMapper.update(any(), any())).thenReturn(1);

        InspectionTask queued = executionService.queue("task-1");

        var captor = org.mockito.ArgumentCaptor.forClass(TaskItem.class);
        verify(taskItemMapper, times(1)).insert(captor.capture());
        verify(taskMapper).updateById(task);
        assertThat(captor.getValue().getMessageId()).isEqualTo("message-2");
        assertThat(task.getProcessedMessages()).isEqualTo(1);
        assertThat(task.getFailedMessages()).isZero();
        assertThat(queued).isSameAs(task);
    }

    @Test
    void repeatedRetryPreservesSuccessesFromAllEarlierAttempts() {
        InspectionTask task = task("PARTIAL_FAILED");
        task.setTotalMessages(52);
        List<TaskItem> failedItems = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(index -> item("message-" + index, "FAILED"))
                .toList();
        List<ConversationMessage> failedMessages = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(index -> message("message-" + index, index))
                .toList();
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(dataScope.canView(null, null)).thenReturn(true);
        when(taskItemMapper.selectList(any())).thenReturn(failedItems);
        when(messageMapper.selectList(any())).thenReturn(failedMessages);
        when(taskMapper.update(any(), any())).thenReturn(1);

        InspectionTask queued = executionService.queue("task-1");

        verify(taskItemMapper, times(9)).insert(any(TaskItem.class));
        assertThat(queued.getProcessedMessages()).isEqualTo(43);
        assertThat(queued.getFailedMessages()).isZero();
    }

    @Test
    void runningTaskCancellationWaitsForSafeExecutionBoundary() {
        InspectionTask task = task("RUNNING");
        task.setCurrentExecutionId("execution-1");
        TaskExecution execution = new TaskExecution();
        execution.setId("execution-1"); execution.setStatus("RUNNING");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(dataScope.canView(null, null)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(executionMapper.selectById("execution-1")).thenReturn(execution);

        taskService.cancel("task-1");

        assertThat(execution.getStatus()).isEqualTo("RUNNING");
        verify(executionMapper, never()).updateById(execution);
    }

    @Test
    void taskCreationRequiresPublishedAgent() {
        stubVisibleConversation();

        assertThatThrownBy(() -> taskService.create("task", "conversation-1", null, null, List.of("rule-1")))
                .isInstanceOf(IqcException.class)
                .hasMessageContaining("Agent");
    }

    @Test
    void explicitRuleOnlyTaskDoesNotRequireOrSnapshotAgent() throws Exception {
        stubVisibleConversation();
        stubPublishedRule("rule-1", "KEYWORD");
        var task = taskService.createBatch("rules", List.of("conversation-1"), null, null, List.of("rule-1"), 1, "RULE_ONLY");
        assertThat(task.getAgentId()).isNull();
        assertThat(task.getAgentSnapshotJson()).isNull();
        var snapshot = new ObjectMapper().readTree(task.getRuleSnapshotJson());
        assertThat(snapshot.path("executionStrategy").path("mode").asText()).isEqualTo("RULE_ONLY");
        assertThat(snapshot.path("rules")).hasSize(1);
        verify(agentMapper, never()).selectById(any(String.class));
    }

    @Test
    void pureRuleTaskRejectsLlmRatherThanSilentlySkippingIt() {
        stubVisibleConversation();
        stubPublishedRule("rule-1", "LLM");
        assertThatThrownBy(() -> taskService.createBatch("rules", List.of("conversation-1"), null, null, List.of("rule-1"), 1, "RULE_ONLY"))
                .hasMessageContaining("不能包含 LLM");
        verify(taskMapper, never()).insert(any(InspectionTask.class));
    }

    @Test
    void scheduledPureRuleTaskAlsoFreezesStrategyWithoutAgent() {
        stubPublishedRule("rule-1", "KEYWORD");
        var task = taskService.createScheduled("later", null, java.time.LocalDateTime.now().plusDays(1),
                null, null, List.of("rule-1"), 1, "RULE_ONLY");
        assertThat(task.getStatus()).isEqualTo("SCHEDULED");
        assertThat(task.getRuleSnapshotJson()).contains("executionStrategy", "RULE_ONLY");
        assertThat(task.getAgentId()).isNull();
    }

    @Test
    void capabilityAgentNeedsTaskStrategyAndDoesNotSupplyImplicitMode() {
        stubVisibleConversation();
        QualityAgent agent = new QualityAgent(); agent.setId("agent-1"); agent.setStatus("PUBLISHED");
        agent.setConfigJson("{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"judge\"}");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        stubPublishedRule("rule-1", "LLM");
        assertThatThrownBy(() -> taskService.createBatch("llm", List.of("conversation-1"), "agent-1", null, List.of("rule-1"), 1))
                .hasMessageContaining("任务中选择执行策略");
        var task = taskService.createBatch("llm", List.of("conversation-1"), "agent-1", null, List.of("rule-1"), 1, "INDEPENDENT");
        assertThat(task.getRuleSnapshotJson()).contains("INDEPENDENT");
        assertThat(task.getAgentSnapshotJson()).doesNotContain("INDEPENDENT");
    }

    @Test
    void pureLlmStrategyCannotSilentlyOmitSelectedLocalChecks() {
        stubVisibleConversation();
        QualityAgent agent = new QualityAgent(); agent.setId("agent-1"); agent.setStatus("PUBLISHED");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        stubPublishedRule("rule-1", "KEYWORD");
        assertThatThrownBy(() -> taskService.createBatch("llm", List.of("conversation-1"), "agent-1", null, List.of("rule-1"), 1, "AGENT_LLM"))
                .hasMessageContaining("不能静默跳过");
    }

    @Test
    void legacyMixedSelectionIsRejectedUntilBusinessScoringCanSeparateLabelDependencies() {
        var selection = new LabelResolutionService.LabelSelection(List.of(), List.of(), List.of("label-1"), List.of());
        when(labelResolution.resolve(selection)).thenReturn(new LabelResolutionService.ResolvedSelection("1.0", List.of(), List.of("tag-rule")));
        assertThatThrownBy(() -> taskService.createConfigured("mixed", "BATCH", List.of("conversation-1"), null,
                null, null, null, "agent-1", null, List.of("rule-1"), 1, selection, null, "INDEPENDENT"))
                .hasMessageContaining("逐项评分");
        verify(taskMapper, never()).insert(any(InspectionTask.class));
    }

    @Test
    void sampledPureRuleTaskKeepsStrategyAndSelectionSeed() {
        stubVisibleConversation();
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation("conversation-1", 2)));
        stubPublishedRule("rule-1", "KEYWORD");
        var task = taskService.createSampled("sample", null, 1, "fixed-seed", null, null, List.of("rule-1"), 1, "RULE_ONLY");
        assertThat(task.getTaskType()).isEqualTo("SAMPLE");
        assertThat(task.getRuleSnapshotJson()).contains("RULE_ONLY");
        assertThat(task.getSelectionFilterJson()).contains("fixed-seed", "conversation-1");
    }

    private void stubPublishedRule(String id, String type) {
        var rule = new io.github.opensabre.iqc.rule.model.QualityRule();
        rule.setId(id); rule.setRuleType(type); rule.setStatus("PUBLISHED"); rule.setVersionNo(2);
        when(ruleMapper.selectById(id)).thenReturn(rule);
    }

    @Test
    void taskCreationRequiresAtLeastOneRule() {
        stubVisibleConversation();
        QualityAgent agent = new QualityAgent();
        agent.setId("agent-1");
        agent.setStatus("PUBLISHED");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        assertThatThrownBy(() -> taskService.create("task", "conversation-1", "agent-1", null, List.of()))
                .isInstanceOf(IqcException.class)
                .hasMessageContaining("规则");
    }

    @Test
    void batchCreationSnapshotsConversationsAndAggregatesMessageCount() throws Exception {
        Conversation first = conversation("conversation-1", 2);
        Conversation second = conversation("conversation-2", 3);
        when(conversationMapper.selectById("conversation-1")).thenReturn(first);
        when(conversationMapper.selectById("conversation-2")).thenReturn(second);
        when(dataScope.canView(null, null)).thenReturn(true);
        QualityAgent agent = new QualityAgent(); agent.setId("agent-1"); agent.setStatus("PUBLISHED");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        io.github.opensabre.iqc.rule.model.QualityRule rule = new io.github.opensabre.iqc.rule.model.QualityRule();
        rule.setId("rule-1"); rule.setStatus("PUBLISHED");
        when(ruleMapper.selectById("rule-1")).thenReturn(rule);

        InspectionTask created = taskService.createBatch("batch", List.of("conversation-1", "conversation-2"),
                "agent-1", null, List.of("rule-1"), 4);

        assertThat(created.getTaskType()).isEqualTo("BATCH");
        assertThat(created.getConversationId()).isNull();
        assertThat(created.getConcurrencyLimit()).isEqualTo(4);
        assertThat(created.getTotalMessages()).isEqualTo(5);
        assertThat(new ObjectMapper().readTree(created.getConversationIdsJson())).hasSize(2);
        verify(taskMapper).insert(created);
    }

    @Test
    void batchCreationExpandsPublishedRuleSetIntoImmutableSnapshot() throws Exception {
        Conversation conversation = conversation("conversation-1", 2);
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(dataScope.canView(null, null)).thenReturn(true);
        QualityAgent agent = new QualityAgent(); agent.setId("agent-1"); agent.setStatus("PUBLISHED");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        var rule = new io.github.opensabre.iqc.rule.model.QualityRule(); rule.setId("rule-1"); rule.setStatus("PUBLISHED");
        when(ruleMapper.selectById("rule-1")).thenReturn(rule);
        when(ruleSetService.published("set-1")).thenReturn(
                new QualityRuleSetService.PublishedRuleSet("set-1", "服务规范", "SERVICE_STANDARD", 3, "ALL", List.of("rule-1")));

        InspectionTask created = taskService.createBatch("set task", List.of("conversation-1"),
                "agent-1", "set-1", List.of(), 1);

        var snapshot = new ObjectMapper().readTree(created.getRuleSnapshotJson());
        assertThat(created.getRuleSetId()).isEqualTo("set-1");
        assertThat(snapshot.path("ruleSetName").asText()).isEqualTo("服务规范");
        assertThat(snapshot.path("ruleSetCode").asText()).isEqualTo("SERVICE_STANDARD");
        assertThat(snapshot.path("ruleSetVersion").asInt()).isEqualTo(3);
        assertThat(snapshot.path("aggregationMode").asText()).isEqualTo("ALL");
        assertThat(snapshot.path("rules")).hasSize(1);
    }

    @Test
    void scheduledTaskResolvesCurrentMatchingConversationsOnlyWhenDue() {
        QualityAgent agent = new QualityAgent(); agent.setId("agent-1"); agent.setStatus("PUBLISHED");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        io.github.opensabre.iqc.rule.model.QualityRule rule = new io.github.opensabre.iqc.rule.model.QualityRule();
        rule.setId("rule-1"); rule.setStatus("PUBLISHED"); when(ruleMapper.selectById("rule-1")).thenReturn(rule);
        when(dataScope.owner()).thenReturn("alice"); when(dataScope.groupId()).thenReturn("group-1");
        var scheduledTime = java.time.LocalDateTime.now().plusMinutes(10);
        InspectionTask scheduled = taskService.createScheduled("nightly",
                new InspectionTaskService.ScheduledFilter(null, null, "service", "IMPORTED", null, 50),
                scheduledTime, "agent-1", null, List.of("rule-1"), 3);
        scheduled.setId("scheduled-1");
        Conversation matching = conversation("conversation-new", 7); matching.setStatus("IMPORTED");
        when(taskMapper.selectList(any())).thenReturn(List.of(scheduled));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectList(any())).thenReturn(List.of(matching));

        List<InspectionTask> ready = taskService.materializeDue(scheduledTime.plusSeconds(1));

        assertThat(ready).containsExactly(scheduled);
        assertThat(scheduled.getStatus()).isEqualTo("CREATED");
        assertThat(scheduled.getTotalMessages()).isEqualTo(7);
        assertThat(scheduled.getConversationId()).isEqualTo("conversation-new");
        assertThat(scheduled.getSelectionFilterJson()).contains("scopeOwner", "alice", "service");
        verify(taskMapper).insert(scheduled);
    }

    @Test
    void deletingTerminalTaskKeepsTheTaskRecord() {
        InspectionTask task = task("SUCCEEDED");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(dataScope.canView(null, null)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(1);

        taskService.deleteTerminal("task-1");

        verify(taskMapper, never()).deleteById("task-1");
        verify(taskMapper).update(any(), any());
    }

    @Test
    void listsExecutionAttemptsOnlyAfterTaskDataScopeAuthorization() {
        InspectionTask task = task("task-1"); task.setCurrentExecutionId("run-2");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(dataScope.canView(null, null)).thenReturn(true);
        TaskExecution execution = new TaskExecution(); execution.setId("run-2"); execution.setTaskId("task-1");
        execution.setAttemptNo(2); execution.setStatus("SUCCEEDED");
        when(executionMapper.selectList(any())).thenReturn(List.of(execution));

        var runs = taskService.executions("task-1");

        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo("run-2");
            assertThat(run.attemptNo()).isEqualTo(2);
            assertThat(run.current()).isTrue();
        });

        when(dataScope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> taskService.executions("task-1")).hasMessageContaining("无权");
        verify(executionMapper, times(1)).selectList(any());
    }

    private void stubVisibleConversation() {
        Conversation conversation = conversation("conversation-1", 1);
        conversation.setSourceFileName("conversation.txt");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(dataScope.canView(null, null)).thenReturn(true);
    }

    @Test
    void schemeTaskFreezesPublishedVersionWithoutLookingUpMutableRulesOrAgent() throws Exception {
        stubVisibleConversation();
        var version = schemeVersion();
        var task = taskService.createFromScheme("模板任务", List.of("conversation-1"), 1, version);
        var snapshot = new ObjectMapper().readTree(task.getRuleSnapshotJson());
        assertThat(snapshot.path("schemeSnapshot").path("versionNo").asInt()).isEqualTo(3);
        assertThat(snapshot.path("schemeSnapshot").path("contentHash").asText()).isEqualTo(version.getContentHash());
        assertThat(snapshot.path("executionStrategy").path("mode").asText()).isEqualTo("RULE_ONLY");
        assertThat(task.getAgentId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(ruleMapper, agentMapper, ruleSetService);
        verify(taskMapper).insert(task);
    }

    @Test
    void scheduledSchemeFreezesReleaseAndScopeButCapsDataResolutionAtDueTime() throws Exception {
        when(dataScope.canViewAll()).thenReturn(false);
        when(dataScope.owner()).thenReturn("alice");
        when(dataScope.groupId()).thenReturn("group-a");
        var version = limitedSchemeVersion(2, 2, 3);
        var scheduledAt = java.time.LocalDateTime.now().plusMinutes(15);
        var task = taskService.createScheduledFromScheme("定时模板任务",
                new InspectionTaskService.ScheduledFilter(null, null, null, "IMPORTED", null, 50),
                scheduledAt, 3, version, "scheduled-task-1", "schedule-fingerprint");

        var snapshot = new ObjectMapper().readTree(task.getRuleSnapshotJson());
        var selection = new ObjectMapper().readTree(task.getSelectionFilterJson());
        assertThat(task.getTaskType()).isEqualTo("SCHEDULED");
        assertThat(task.getStatus()).isEqualTo("SCHEDULED");
        assertThat(task.getScheduledTime()).isEqualTo(scheduledAt);
        assertThat(task.getConversationIdsJson()).isEqualTo("[]");
        assertThat(task.getConcurrencyLimit()).isEqualTo(3);
        assertThat(snapshot.path("schemeSnapshot").path("kind").asText()).isEqualTo("PUBLISHED");
        assertThat(snapshot.path("schemeSnapshot").path("versionNo").asInt()).isEqualTo(3);
        assertThat(snapshot.path("schemeSnapshot").path("requestFingerprint").asText()).isEqualTo("schedule-fingerprint");
        assertThat(selection.path("filter").path("limit").asInt()).isEqualTo(2);
        assertThat(selection.path("scopeAll").asBoolean()).isFalse();
        assertThat(selection.path("scopeOwner").asText()).isEqualTo("alice");
        assertThat(selection.path("scopeGroupId").asText()).isEqualTo("group-a");
        org.mockito.Mockito.verifyNoInteractions(conversationMapper, ruleMapper, agentMapper, ruleSetService);
        verify(taskMapper).insert(task);
    }

    @Test
    void scheduledSchemeRejectsNonImportedStatusAndClientSuppliedScopeFilters() throws Exception {
        var version = limitedSchemeVersion(2, 2, 3);
        var scheduledAt = java.time.LocalDateTime.now().plusMinutes(15);
        List<InspectionTaskService.ScheduledFilter> unsupported = List.of(
                new InspectionTaskService.ScheduledFilter(null, null, null, "SUCCEEDED", null, 20),
                new InspectionTaskService.ScheduledFilter(null, null, null, "IMPORTED", "other-group", 20),
                new InspectionTaskService.ScheduledFilter(null, null, null, "IMPORTED", null, 20,
                        "employee-1", null, null, null),
                new InspectionTaskService.ScheduledFilter(null, null, null, "IMPORTED", null, 20,
                        null, "customer-1", null, null));

        for (var filter : unsupported) {
            assertThatThrownBy(() -> taskService.createScheduledFromScheme("定时模板任务", filter,
                    scheduledAt, 1, version, "scheduled-task-1", "schedule-fingerprint"))
                    .hasMessageContaining("业务模板定时任务");
        }

        verify(taskMapper, never()).insert(any(InspectionTask.class));
        org.mockito.Mockito.verifyNoInteractions(conversationMapper, ruleMapper, agentMapper, ruleSetService);
    }

    @Test
    void tamperedSchemeSnapshotCannotCreateTask() throws Exception {
        var version = schemeVersion(); version.setSnapshotJson(version.getSnapshotJson().replace("REGEX", "LLM"));
        assertThatThrownBy(() -> taskService.createFromScheme("任务", List.of("conversation-1"), 1, version)).hasMessageContaining("校验失败");
        verify(taskMapper, never()).insert(any(InspectionTask.class));
    }

    @Test
    void ordinaryPublishedTemplateRejectsVariantCodeBeforeBatchOrScheduleInsert() throws Exception {
        var version = schemeVersion();
        assertThatThrownBy(() -> taskService.createFromScheme("任务", List.of("conversation-1"), 1, version,
                "task-id", "fingerprint", "unknown")).hasMessageContaining("未配置");
        assertThatThrownBy(() -> taskService.createScheduledFromScheme("任务", null, java.time.LocalDateTime.now().plusHours(1),
                1, version, "task-id", "fingerprint", "unknown")).hasMessageContaining("未配置");
        verify(taskMapper, never()).insert(any(InspectionTask.class));
        org.mockito.Mockito.verifyNoInteractions(ruleMapper, agentMapper, conversationMapper);
    }

    @Test
    void schemeDoesNotBypassConversationDataScope() throws Exception {
        var version = schemeVersion();
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation("conversation-1", 1));
        assertThatThrownBy(() -> taskService.createFromScheme("任务", List.of("conversation-1"), 1, version)).hasMessageContaining("无权");
        verify(taskMapper, never()).insert(any(InspectionTask.class));
    }

    @Test
    void draftTrialUsesRealTaskSnapshotWithExplicitDraftIdentityAndBoundedData() throws Exception {
        stubVisibleConversation();
        var mapper = new ObjectMapper();
        var release = mapper.readValue(schemeVersion().getSnapshotJson(), io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        var task = taskService.createSchemeTrial("scheme-1", 4, "销售", List.of("conversation-1"), release);
        var snapshot = mapper.readTree(task.getRuleSnapshotJson()).path("schemeSnapshot");
        assertThat(snapshot.path("kind").asText()).isEqualTo("DRAFT_TRIAL");
        assertThat(snapshot.path("draftRevision").asInt()).isEqualTo(4);
        assertThat(snapshot.has("versionNo")).isFalse();
        assertThat(snapshot.path("contentHash").asText()).isEqualTo(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(mapper.writeValueAsString(release)));
        assertThat(task.getConcurrencyLimit()).isEqualTo(1);
        var keyed = taskService.createSchemeTrial("scheme-1", 4, "销售", List.of("conversation-1"), release,
                "tr-stable", "frozen-fingerprint");
        assertThat(keyed.getId()).isEqualTo("tr-stable");
        assertThat(mapper.readTree(keyed.getRuleSnapshotJson()).path("schemeSnapshot").path("requestFingerprint").asText())
                .isEqualTo("frozen-fingerprint");
        assertThatThrownBy(() -> taskService.createSchemeTrial("scheme-1", 4, "销售", java.util.Collections.nCopies(21, "conversation-1"), release))
                .hasMessageContaining("20");
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(any(InspectionTask.class));
    }

    @Test
    void routeTrialRejectsManuallySuppliedReleaseBeforeWritingTask() throws Exception {
        var mapper = new ObjectMapper();
        var release = mapper.readValue(schemeVersion().getSnapshotJson(), io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        var old = release.definition(); var item = old.items().getFirst();
        var definition = new io.github.opensabre.iqc.scheme.SchemeDefinition(old.schemaVersion(),
                List.of(new io.github.opensabre.iqc.scheme.SchemeDefinition.Item(item.itemCode(), item.name(),
                        item.rule(), item.hitMeaning(), null, null,
                        new io.github.opensabre.iqc.scheme.SchemeDefinition.Execution(
                                io.github.opensabre.iqc.scheme.SchemeDefinition.Route.RULE_ONLY, null, null, null, null))),
                old.agent(), old.scoring(), old.runLimits(), old.labels());
        var routed = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(release.name(), release.code(),
                release.description(), release.businessScene(), definition, release.dependencies());
        assertThatThrownBy(() -> taskService.createSchemeTrial("scheme-1", 1, "路线", List.of("conversation-1"), routed))
                .hasMessageContaining("逐项");
        verify(taskMapper, never()).insert(any(InspectionTask.class));
    }

    @Test
    void selectingPublishedVariantRequiresACompleteFrozenRoutePlanAndDoesNotRewriteItsVersion() throws Exception {
        var mapper = new ObjectMapper();
        var version = schemeVersion();
        var old = mapper.readValue(version.getSnapshotJson(), io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        var base = old.definition(); var itemCode = base.items().getFirst().itemCode();
        var route = new io.github.opensabre.iqc.scheme.SchemeDefinition.Execution(
                io.github.opensabre.iqc.scheme.SchemeDefinition.Route.RULE_ONLY, null, null, null, null);
        var variants = new io.github.opensabre.iqc.scheme.SchemeExecutionVariants("standard", List.of(
                new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("standard", "标准", "完整", "本地", java.util.Map.of(itemCode, route)),
                new io.github.opensabre.iqc.scheme.SchemeExecutionVariants.Variant("alternate", "备选", "完整", "本地", java.util.Map.of(itemCode, route))));
        var expanded = variants.expand(base, "standard");
        var definition = new io.github.opensabre.iqc.scheme.SchemeDefinition(base.schemaVersion(), expanded.items(), null,
                base.scoring(), base.runLimits(), base.labels(), variants);
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(old.name(), old.code(), old.description(),
                old.businessScene(), definition, old.dependencies(), "standard", java.util.Map.of("standard", old.dependencies(), "alternate", old.dependencies()));
        String publishedJson = mapper.writeValueAsString(release);
        version.setSnapshotJson(publishedJson);
        version.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(publishedJson));
        assertThatThrownBy(() -> taskService.createFromScheme("任务", List.of("conversation-1"), 1, version,
                "variant-task", "fingerprint", "alternate")).hasMessageContaining("逐项路线");
        assertThatThrownBy(() -> taskService.createScheduledFromScheme("任务", null, java.time.LocalDateTime.now().plusHours(1),
                1, version, "variant-task", "fingerprint", "standard")).hasMessageContaining("逐项路线");
        assertThat(version.getSnapshotJson()).isEqualTo(publishedJson);
        verify(taskMapper, never()).insert(any(InspectionTask.class));
        org.mockito.Mockito.verifyNoInteractions(ruleMapper, agentMapper, conversationMapper);
    }

    @Test
    void templateLimitsDefaultAndExplicitConcurrencyUseFrozenRelease() throws Exception {
        stubVisibleConversation();
        var version = limitedSchemeVersion(2, 2, 3);
        var task = taskService.createFromScheme("任务", List.of("conversation-1"), null, version);
        assertThat(task.getConcurrencyLimit()).isEqualTo(2);
        assertThat(new ObjectMapper().readTree(task.getRuleSnapshotJson()).path("schemeSnapshot")
                .path("release").path("definition").path("runLimits").path("maxConcurrency").asInt()).isEqualTo(3);
        assertThat(taskService.createFromScheme("任务", List.of("conversation-1"), 3, version).getConcurrencyLimit()).isEqualTo(3);
    }

    @Test
    void templateLimitsRejectBeforeReadingConversationsOrWritingTasks() throws Exception {
        var version = limitedSchemeVersion(1, 1, 2);
        assertThatThrownBy(() -> taskService.createFromScheme("任务", List.of("c1", "c2"), 1, version))
                .hasMessageContaining("模板会话上限");
        assertThatThrownBy(() -> taskService.createFromScheme("任务", List.of("c1"), 3, version))
                .hasMessageContaining("模板上限");
        org.mockito.Mockito.verifyNoInteractions(conversationMapper);
        verify(taskMapper, never()).insert(any(InspectionTask.class));
    }

    @Test
    void templateLimitsApplyToTrialsButTrialsRemainSerial() throws Exception {
        stubVisibleConversation();
        var release = new ObjectMapper().readValue(limitedSchemeVersion(1, 2, 3).getSnapshotJson(),
                io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        assertThat(taskService.createSchemeTrial("scheme-1", 1, "试跑", List.of("conversation-1"), release)
                .getConcurrencyLimit()).isEqualTo(1);
        assertThatThrownBy(() -> taskService.createSchemeTrial("scheme-1", 1, "试跑", List.of("c1", "c2"), release))
                .hasMessageContaining("模板会话上限");
    }

    @Test
    void historicalDefinitionRoundTripDoesNotAddLimitsOrChangeReleaseHash() throws Exception {
        var mapper = new ObjectMapper();
        var version = schemeVersion();
        var release = mapper.readValue(version.getSnapshotJson(), io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        assertThat(mapper.writeValueAsString(release)).isEqualTo(version.getSnapshotJson());
        assertThat(mapper.valueToTree(release.definition()).has("runLimits")).isFalse();
        assertThat(release.definition().effectiveRunLimits()).isEqualTo(new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(1000, 1, 32));
    }

    @Test
    void malformedTemplateLimitsAreRejected() {
        assertThatThrownBy(() -> new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(0, 1, 1)).hasMessageContaining("会话上限");
        assertThatThrownBy(() -> new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(1001, 1, 1)).hasMessageContaining("会话上限");
        assertThatThrownBy(() -> new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(1, 0, 1)).hasMessageContaining("默认并发");
        assertThatThrownBy(() -> new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(1, 3, 2)).hasMessageContaining("默认并发");
        assertThatThrownBy(() -> new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(1, 1, 33)).hasMessageContaining("默认并发");
    }

    @Test
    void jointOutputIsFrozenForPublishedTasksAndBoundedDraftTrials() throws Exception {
        var mapper = new ObjectMapper();
        var version = schemeVersion();
        var original = mapper.readValue(version.getSnapshotJson(), io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
        var base = original.definition();
        var definition = new io.github.opensabre.iqc.scheme.SchemeDefinition(base.schemaVersion(), base.items(), base.agent(),
                base.scoring(), base.runLimits(), List.of(new LabelResolutionService.LabelReference("l1", 1)));
        var binding = new io.github.opensabre.iqc.label.model.LabelRuleBinding();
        binding.setRuleId("r1"); binding.setRuleVersionNo(2);
        var value = new io.github.opensabre.iqc.label.model.LabelValueDefinition();
        value.setValueCode("interested"); value.setValueType("BOOLEAN");
        var label = new LabelResolutionService.LabelSnapshot("l1", 1, "意向", "intent", null, null, null, null, null,
                false, "user", null, List.of(binding), List.of(value));
        var frozen = new LabelResolutionService.ResolvedSelection("2.0", List.of(label), List.of("r1"));
        var rule = (com.fasterxml.jackson.databind.node.ObjectNode) original.dependencies().rules().getFirst().deepCopy();
        rule.putArray("labelFactTargets").addObject().put("labelId", "l1").put("labelVersionNo", 1)
                .put("valueCode", "interested").put("valueType", "BOOLEAN").put("subjectRole", "user");
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(original.name(), original.code(),
                original.description(), original.businessScene(), definition,
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.Dependencies(List.of(rule), null, "RULE_ONLY", frozen));
        version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(version.getSnapshotJson()));
        stubVisibleConversation();
        var published = taskService.createFromScheme("联合任务", List.of("conversation-1"), 1, version);
        var publishedSnapshot = mapper.readTree(published.getRuleSnapshotJson());
        assertThat(published.getLabelScopeSnapshotJson()).isNotBlank();
        assertThat(publishedSnapshot.at("/schemeSnapshot/kind").asText()).isEqualTo("PUBLISHED");
        assertThat(publishedSnapshot.at("/schemeSnapshot/release/definition/schemaVersion").asText())
                .isEqualTo(io.github.opensabre.iqc.scheme.SchemeDefinition.JOINT_TASK_SCHEMA);
        assertThat(publishedSnapshot.at("/schemeSnapshot/publishedContentHash").asText()).isEqualTo(version.getContentHash());
        var trial = taskService.createSchemeTrial("scheme-1", 1, "联合试跑", List.of("conversation-1"), release);
        assertThat(trial.getConcurrencyLimit()).isEqualTo(1);
        assertThat(mapper.readTree(trial.getLabelScopeSnapshotJson())).isEqualTo(mapper.valueToTree(frozen));
        assertThat(mapper.readTree(trial.getRuleSnapshotJson()).path("rules").get(0).path("labelFactTargets").size()).isEqualTo(1);
        var labelOnly = new io.github.opensabre.iqc.scheme.SchemeDefinition(base.schemaVersion(), List.of(), null,
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",
                        io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION, 100, 60, List.of()),
                base.runLimits(), definition.labels());
        var labelOnlyRelease = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(original.name(), original.code(),
                original.description(), original.businessScene(), labelOnly,
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.Dependencies(List.of(rule), null, "RULE_ONLY", frozen));
        var labelOnlyTrial = taskService.createSchemeTrial("scheme-1", 1, "纯画像试跑", List.of("conversation-1"), labelOnlyRelease);
        assertThat(mapper.readTree(labelOnlyTrial.getRuleSnapshotJson()).path("schemeSnapshot").path("release")
                .path("definition").path("items").isEmpty()).isTrue();
        assertThat(labelOnlyTrial.getLabelScopeSnapshotJson()).isNotBlank();
        verify(taskMapper, times(3)).insert(any(InspectionTask.class));
    }

    private io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion limitedSchemeVersion(int maxConversations, int defaultConcurrency, int maxConcurrency) throws Exception {
        var version = schemeVersion();
        var mapper = new ObjectMapper();
        var json = mapper.readTree(version.getSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("definition")).set("runLimits",
                mapper.valueToTree(new io.github.opensabre.iqc.scheme.SchemeDefinition.RunLimits(maxConversations, defaultConcurrency, maxConcurrency)));
        version.setSnapshotJson(mapper.writeValueAsString(json));
        version.setContentHash(io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(version.getSnapshotJson()));
        return version;
    }

    private io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion schemeVersion() throws Exception {
        var mapper = new ObjectMapper();
        var definition = new io.github.opensabre.iqc.scheme.SchemeDefinition("iqc-scheme-v2",
                List.of(new io.github.opensabre.iqc.scheme.SchemeDefinition.Item("greeting", "开场白",
                        new io.github.opensabre.iqc.scheme.SchemeDefinition.RuleReference("r1", 2),
                        io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.COMPLIANCE)), null,
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",
                        io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new io.github.opensabre.iqc.scoring.InspectionScoring.Item("greeting", 10, false))));
        var rule = mapper.createObjectNode().put("id", "r1").put("versionNo", 2).put("ruleType", "REGEX").put("expression", "您好");
        var release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot("销售方案", "sales", null, "销售", definition,
                new io.github.opensabre.iqc.scheme.SchemeDependencyResolver.Dependencies(List.of(rule), null, "RULE_ONLY"));
        var version = new io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion();
        version.setSchemeId("scheme-1"); version.setVersionNo(3); version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(version.getSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return version;
    }

    private Conversation conversation(String id, int messageCount) {
        Conversation conversation = new Conversation();
        conversation.setId(id); conversation.setSourceFileName(id + ".txt"); conversation.setMessageCount(messageCount);
        return conversation;
    }

    private InspectionTask task(String status) {
        InspectionTask task = new InspectionTask();
        task.setId("task-1"); task.setConversationId("conversation-1"); task.setStatus(status);
        task.setCurrentExecutionId("execution-old"); task.setAttemptCount(1);
        return task;
    }

    private TaskItem item(String messageId, String status) {
        TaskItem item = new TaskItem();
        item.setMessageId(messageId); item.setExecutionId("execution-old"); item.setStatus(status);
        return item;
    }

    private ConversationMessage message(String id, int sequence) {
        ConversationMessage message = new ConversationMessage();
        message.setId(id); message.setConversationId("conversation-1"); message.setSequenceNo(sequence);
        message.setSpeakerRole("agent"); message.setContent("message");
        return message;
    }
}
