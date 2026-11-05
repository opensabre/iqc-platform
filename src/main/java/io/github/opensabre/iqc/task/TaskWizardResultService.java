package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskItem;
import io.github.opensabre.iqc.task.processing.EngineExecutionResultSet;
import io.github.opensabre.iqc.task.processing.TaskResultProcessor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits derived conversation results and item progress together after external engine calls finish. */
@Service
public class TaskWizardResultService {
    private final InspectionTaskMapper tasks;
    private final TaskItemMapper items;
    private final ConversationInspectionResultMapper results;
    private final ObjectMapper mapper;
    public TaskWizardResultService(InspectionTaskMapper tasks, TaskItemMapper items,
                                   ConversationInspectionResultMapper results, ObjectMapper mapper) {
        this.tasks = tasks; this.items = items; this.results = results; this.mapper = mapper;
    }
    /** Locks the task only for the short commit; stale, paused or cancelled workers cannot publish results. */
    @Transactional
    public void commit(InspectionTask original, EngineExecutionResultSet set, TaskResultProcessor.ProcessedResult processed, List<TaskItem> batch) {
        var task = tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId, original.getId()).last("FOR UPDATE"));
        if (task == null || !List.of("RUNNING", "PAUSE_REQUESTED").contains(task.getStatus())
                || !set.executionId().equals(task.getCurrentExecutionId())) return;
        if (!java.util.Objects.equals(task.getRuleSnapshotJson(), original.getRuleSnapshotJson()))
            throw new IllegalStateException("任务冻结配置已改变");
        var output = processed.output();
        var result = results.selectForTaskExecutionConversation(task.getId(), set.executionId(), set.conversationId());
        if (result == null) {
            result = new ConversationInspectionResult(); result.setTaskId(task.getId()); result.setExecutionId(set.executionId());
            result.setConversationId(set.conversationId()); result.setCreatedBy(task.getCreatedBy());
        }
        result.setAggregationMode("TASK_PROCESSOR");
        result.setScore(null);
        var scoring = output.get("scoring");
        result.setScoreStatus(scoring == null ? "NOT_APPLICABLE" : scoring.path("scoreStatus").asText());
        result.setFinalScore(scoring == null || !scoring.hasNonNull("finalScore") ? null : scoring.path("finalScore").decimalValue());
        result.setResultStatus(scoring == null ? "NOT_APPLICABLE" : scoring.path("conclusion").asText());
        try {
            result.setScoringResultJson(scoring == null ? null : mapper.writeValueAsString(scoring));
            // The complete processed payload, including label candidates, stays linked to source engine records.
            result.setBusinessItemResultsJson(mapper.writeValueAsString(processed));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(invalid); }
        int written = result.getId() == null ? results.insert(result) : results.updateById(result);
        if (written != 1) throw new IllegalStateException("任务结果保存失败");
        boolean failed = set.engines().stream().anyMatch(engine -> engine.status() == EngineExecutionResultSet.ExecutionStatus.FAILED);
        for (var requested : batch) {
            var item = items.selectById(requested.getId());
            if (item == null || !task.getId().equals(item.getTaskId()) || !set.executionId().equals(item.getExecutionId())
                    || !set.conversationId().equals(item.getConversationId())) throw new IllegalStateException("任务进度身份不一致");
            if (List.of("SUCCEEDED", "CANCELLED").contains(item.getStatus())) continue;
            item.setStatus(failed ? "FAILED" : "SUCCEEDED");
            item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1);
            item.setErrorMessage(failed ? "存在引擎执行失败，原始结果已保存" : null);
            if (items.updateById(item) != 1) throw new IllegalStateException("任务进度保存失败");
        }
    }
}
