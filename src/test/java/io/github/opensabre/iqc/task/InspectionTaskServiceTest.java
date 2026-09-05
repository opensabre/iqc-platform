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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectionTaskServiceTest {
    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "iqc-test"), InspectionTask.class);
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
    private final InspectionExecutionService executionService = new InspectionExecutionService(taskMapper, conversationMapper, messageMapper, resultMapper,
            new ObjectMapper(), executionMapper, taskItemMapper, dataScope, mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class), mock(UsageCounterRecorder.class));
    private final InspectionTaskService taskService = new InspectionTaskService(taskMapper, conversationMapper, agentMapper, ruleMapper, ruleSetService,
            new ObjectMapper(), executionMapper, dataScope);

    @Test
    void retryOnlyQueuesFailedMessagesAndPreservesSuccessfulProgress() {
        InspectionTask task = task("PARTIAL_FAILED");
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
    void runningTaskCancellationAlsoCancelsCurrentExecution() {
        InspectionTask task = task("RUNNING");
        task.setCurrentExecutionId("execution-1");
        TaskExecution execution = new TaskExecution();
        execution.setId("execution-1"); execution.setStatus("RUNNING");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(dataScope.canView(null, null)).thenReturn(true);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(executionMapper.selectById("execution-1")).thenReturn(execution);

        taskService.cancel("task-1");

        assertThat(execution.getStatus()).isEqualTo("CANCELLED");
        verify(executionMapper).updateById(execution);
    }

    @Test
    void taskCreationRequiresPublishedAgent() {
        stubVisibleConversation();

        assertThatThrownBy(() -> taskService.create("task", "conversation-1", null, null, List.of("rule-1")))
                .isInstanceOf(IqcException.class)
                .hasMessageContaining("Agent");
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

    private void stubVisibleConversation() {
        Conversation conversation = conversation("conversation-1", 1);
        conversation.setSourceFileName("conversation.txt");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(dataScope.canView(null, null)).thenReturn(true);
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
