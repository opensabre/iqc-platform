package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.ConversationSpeakerRole;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.task.model.TaskItem;
import io.github.opensabre.iqc.task.processing.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Runs prototype tasks inside the existing conversation worker, retaining checkpoints before advancing engines. */
@Service
public final class TaskWizardRuntime {
    private final InspectionTaskMapper tasks;
    private final ConversationMessageMapper messages;
    private final TaskExecutionMapper executions;
    private final EngineResultSetStore store;
    private final TaskEnginePlanService plans;
    private final ConfiguredTaskResultProcessor processor;
    private final TaskWizardResultService results;
    private final ObjectMapper mapper;
    private final UnifiedTaskResultProcessor unifiedProcessor;
    public TaskWizardRuntime(InspectionTaskMapper tasks, ConversationMessageMapper messages, TaskExecutionMapper executions,
                             EngineResultSetStore store, TaskEnginePlanService plans, ConfiguredTaskResultProcessor processor,
                             TaskWizardResultService results, ObjectMapper mapper, UnifiedTaskResultProcessor unifiedProcessor) {
        this.tasks = tasks; this.messages = messages; this.executions = executions; this.store = store;
        this.plans = plans; this.processor = processor; this.results = results; this.mapper = mapper; this.unifiedProcessor = unifiedProcessor;
    }
    /** Pause takes effect between conversations; cancellation is checked before each engine. */
    public void execute(InspectionTask task, String executionId, TaskWizardSnapshot snapshot, List<TaskItem> items) {
        if (items.isEmpty()) return;
        var current = tasks.selectById(task.getId());
        if (current == null || !"RUNNING".equals(current.getStatus()) || !executionId.equals(current.getCurrentExecutionId())) return;
        String conversationId = items.getFirst().getConversationId();
        if (conversationId == null || items.stream().anyMatch(item -> !conversationId.equals(item.getConversationId())))
            throw new IllegalArgumentException("任务会话进度身份无效");
        var saved = store.find(executionId, conversationId).orElseGet(() -> initial(task, executionId, conversationId, snapshot));
        if (!saved.taskId().equals(task.getId()) || !saved.taskConfiguration().equals(mapper.valueToTree(snapshot)))
            throw new IllegalStateException("保存的结果集与当前任务不一致");
        var frozenIds = saved.input().messages().stream().map(Message::messageId).collect(java.util.stream.Collectors.toSet());
        if (items.stream().anyMatch(item -> !frozenIds.contains(item.getMessageId())))
            throw new IllegalStateException("任务进度消息不属于冻结输入");
        var completed = new MultiEngineExecutor(Clock.systemUTC()).execute(saved, plans.steps(snapshot.enginePlan(), conversationId),
                () -> cancelled(task.getId(), executionId), store::save);
        if (cancelled(task.getId(), executionId)) return;
        var processed = snapshot.unifiedLabels() == null
                ? processor.process(completed, mapper.valueToTree(snapshot.resultProcessing().configuration()))
                : unifiedProcessor.process(completed, mapper.getNodeFactory().nullNode());
        results.commit(task, completed, processed, items);
    }
    private EngineExecutionResultSet initial(InspectionTask task, String execution, String conversation, TaskWizardSnapshot snapshot) {
        var currentExecution = executions.selectById(execution);
        var query = Wrappers.<TaskExecution>lambdaQuery().eq(TaskExecution::getTaskId, task.getId()).ne(TaskExecution::getId, execution);
        if (currentExecution == null || currentExecution.getRunId() == null || !task.getId().equals(currentExecution.getTaskId()))
            throw new IllegalStateException("执行缺少有效运行轮次");
        query.eq(TaskExecution::getRunId, currentExecution.getRunId());
        var previousRuns = executions.selectList(query.orderByDesc(TaskExecution::getAttemptNo));
        // A new retry attempt keeps the original input and successful engine outputs; failed engines run again.
        for (var previous : previousRuns) {
            var prior = store.find(previous.getId(), conversation);
            if (prior.isPresent()) {
                var original = prior.get();
                if (!original.taskId().equals(task.getId()) || !original.taskConfiguration().equals(mapper.valueToTree(snapshot)))
                    throw new IllegalStateException("重试不能改变已冻结输入配置");
                return new EngineExecutionResultSet(SCHEMA_VERSION, UUID.randomUUID().toString(), task.getId(), execution,
                        conversation, original.input(), original.taskConfiguration(), snapshot.unifiedLabels() == null ? original.engines().stream()
                        .filter(engine -> engine.status() == ExecutionStatus.SUCCEEDED).toList()
                        : original.engines().stream().takeWhile(engine -> engine.status() == ExecutionStatus.SUCCEEDED
                                || engine.status() == ExecutionStatus.SKIPPED).toList());
            }
        }
        var context = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getConversationId, conversation)
                .orderByAsc(ConversationMessage::getSequenceNo));
        if (context == null || context.isEmpty()) throw new IllegalStateException("执行会话缺少已处理消息");
        ConversationSpeakerRole.canonicalize(context);
        var input = new InputSnapshot(UUID.randomUUID().toString(), Instant.now(), context.stream().map(message ->
                new Message(message.getId(), message.getSequenceNo(), message.getSpeakerRole(),
                        message.getRelativeTime() == null ? null : message.getRelativeTime().toString(), message.getContent())).toList());
        return new EngineExecutionResultSet(SCHEMA_VERSION, UUID.randomUUID().toString(), task.getId(), execution,
                conversation, input, mapper.valueToTree(snapshot), List.of());
    }
    private boolean cancelled(String task, String execution) {
        var current = tasks.selectById(task);
        return current == null || !execution.equals(current.getCurrentExecutionId())
                || List.of("CANCELLED", "CANCEL_REQUESTED").contains(current.getStatus());
    }
}
