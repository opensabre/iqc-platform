package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.Set;

/** Task first, then Execution: one transactional state boundary for the existing persistent rows. */
@Service @RequiredArgsConstructor
public class TaskExecutionStateService {
    private final InspectionTaskMapper tasks;
    private final TaskExecutionMapper executions;
    private InspectionTask lock(String taskId) {
        return tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,taskId).last("FOR UPDATE"));
    }
    private TaskExecution execution(InspectionTask task,String executionId) {
        if(task==null || executionId==null || !executionId.equals(task.getCurrentExecutionId()))return null;
        var row=executions.selectOne(Wrappers.<TaskExecution>lambdaQuery().eq(TaskExecution::getId,executionId)
                .eq(TaskExecution::getTaskId,task.getId()).last("FOR UPDATE"));
        return row;
    }
    private static void requireUpdated(int changed) {
        if(changed!=1)throw new IllegalStateException("Persistent task/execution state update lost its locked row");
    }
    @Transactional
    public boolean claim(String taskId,String executionId) {
        var task=lock(taskId);var row=execution(task,executionId);
        if(row==null || !"QUEUED".equals(task.getStatus()) || !"QUEUED".equals(row.getStatus()))return false;
        int changed=tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,taskId)
                .eq(InspectionTask::getCurrentExecutionId,executionId).eq(InspectionTask::getStatus,"QUEUED")
                .set(InspectionTask::getStatus,"RUNNING").set(InspectionTask::getUpdatedTime,new Date()));
        if(changed!=1)return false;
        requireUpdated(executions.update(null,Wrappers.<TaskExecution>lambdaUpdate().eq(TaskExecution::getId,executionId)
                .eq(TaskExecution::getTaskId,taskId).set(TaskExecution::getStatus,"RUNNING")));
        return true;
    }
    @Transactional
    public InspectionTask finish(String taskId,String executionId,int processed,int failed) {
        var task=lock(taskId);var row=execution(task,executionId);
        if(row==null || !Set.of("RUNNING","PAUSE_REQUESTED","CANCEL_REQUESTED","CANCELLED").contains(task.getStatus())
                || !Set.of("RUNNING","QUEUED").contains(row.getStatus()))return task;
        String status=switch(task.getStatus()) {
            case "PAUSE_REQUESTED" -> "PAUSED";
            case "CANCEL_REQUESTED","CANCELLED" -> "CANCELLED";
            default -> failed>0?"PARTIAL_FAILED":"SUCCEEDED";
        };
        task.setProcessedMessages(processed);task.setFailedMessages(failed);task.setStatus(status);
        requireUpdated(tasks.updateById(task));
        row.setProcessedMessages(processed);row.setFailedMessages(failed);row.setStatus(status);requireUpdated(executions.updateById(row));
        return task;
    }
    @Transactional
    public boolean fail(String taskId,String executionId,boolean claimed,String error) {
        var task=lock(taskId);var row=execution(task,executionId);
        var allowed=claimed?Set.of("RUNNING","PAUSE_REQUESTED","CANCEL_REQUESTED"):Set.of("QUEUED");
        if(row==null || !allowed.contains(task.getStatus()) || !Set.of("QUEUED","RUNNING").contains(row.getStatus()))return false;
        String status=switch(task.getStatus()) {case "PAUSE_REQUESTED"->"PAUSED";case "CANCEL_REQUESTED"->"CANCELLED";default->"FAILED";};
        if("FAILED".equals(status))task.setFailedMessages(Math.max(0,(task.getTotalMessages()==null?0:task.getTotalMessages())
                -(task.getProcessedMessages()==null?0:task.getProcessedMessages())));
        task.setStatus(status);requireUpdated(tasks.updateById(task));
        row.setStatus(status);row.setErrorMessage(error);requireUpdated(executions.updateById(row));
        return true;
    }
}
