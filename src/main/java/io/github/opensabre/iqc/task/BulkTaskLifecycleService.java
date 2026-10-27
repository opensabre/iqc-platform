package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Set;

/** A bulk parent has no synthetic message items. Existing child tasks own all model work. */
@Service @RequiredArgsConstructor
public class BulkTaskLifecycleService {
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED","CANCELLED","NO_DATA");
    private final InspectionTaskMapper tasks;
    private final TaskExecutionMapper executions;
    private final BulkTaskMaterializationService materialization;
    private final InspectionTaskService taskService;
    private final InspectionExecutionService execution;
    private final IqcDataScope scope;

    /** Read only the original principal before calling organization outside all task locks. */
    public ScheduledActorAccessService.CreationBound ownerForDispatch(String taskId,String executionId) {
        ScheduledActorAccessService.requireBackground();
        var parent=tasks.selectById(taskId);
        if(parent==null || !java.util.Objects.equals(parent.getCurrentExecutionId(),executionId))
            throw IqcException.invalidState("批次执行已变化");
        return materialization.definition(parent).ownerBound();
    }

    /** Authorized immutable owner evidence only; never exports the parent configuration to the caller. */
    public ScheduledActorAccessService.CreationBound ownerForCurrent(String id) {
        var parent=tasks.selectById(id);
        if(parent==null || !scope.canView(parent.getCreatedBy(),parent.getOwnerGroupId()))
            throw IqcException.accessDenied("无权恢复此批次");
        return materialization.definition(parent).ownerBound();
    }

    /** Current-user caller enters the already resolved owner scope; a failed round gets a new execution. */
    @Transactional
    public InspectionTask queue(String id) {
        var parent=lock(id);requireOwner(parent);
        if("QUEUED".equals(parent.getStatus()) || "RUNNING".equals(parent.getStatus()))return parent;
        if(!Set.of("CREATED","PAUSED","FAILED","PARTIAL_FAILED").contains(parent.getStatus()))
            throw IqcException.invalidState("当前批次不能启动或恢复");
        boolean resume="PAUSED".equals(parent.getStatus());
        TaskExecution row;
        if(resume) {
            row=run(parent);
            if(row==null || !"PAUSED".equals(row.getStatus()))throw IqcException.invalidState("批次暂停轮次无效");
        } else {
            row=new TaskExecution();row.setTaskId(id);row.setAttemptNo(Math.addExact(parent.getAttemptCount()==null?0:parent.getAttemptCount(),1));
            row.setProcessedMessages(0);row.setFailedMessages(0);row.setStatus("QUEUED");
            requireChanged(executions.insert(row));parent.setCurrentExecutionId(row.getId());parent.setAttemptCount(row.getAttemptNo());
        }
        parent.setStatus("QUEUED");parent.setPauseRequested(false);parent.setCancelRequested(false);parent.setUpdatedTime(new Date());
        row.setStatus("QUEUED");row.setErrorMessage(null);
        requireChanged(tasks.updateById(parent));requireChanged(executions.updateById(row));
        // The child resume guard sees the parent's new state in this same transaction.
        // Failure rolls back parent and children together; no replacement task or input is created.
        for(var child:pending(id))if("PAUSED".equals(child.getStatus()))execution.resume(child.getId());
        return parent;
    }

    @Transactional
    public boolean claim(String id,String executionId) {
        var parent=lock(id);requireOwner(parent);
        if(!java.util.Objects.equals(executionId,parent.getCurrentExecutionId()))return false;
        var row=run(parent);
        if(row==null || !"QUEUED".equals(row.getStatus())
                || !Set.of("QUEUED","PAUSE_REQUESTED","CANCEL_REQUESTED").contains(parent.getStatus()))return false;
        if("QUEUED".equals(parent.getStatus()))parent.setStatus("RUNNING");
        parent.setUpdatedTime(new Date());row.setStatus("RUNNING");
        requireChanged(tasks.updateById(parent));requireChanged(executions.updateById(row));return true;
    }

    /** One durable tick: either control the active child, dispatch one child, or scan one bounded page. */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void tick(String id,String executionId) {
        var parent=lock(id);requireOwner(parent);var row=run(parent);
        if(row==null || !executionId.equals(parent.getCurrentExecutionId()) || !"RUNNING".equals(row.getStatus()))return;
        if(!Set.of("RUNNING","PAUSE_REQUESTED","CANCEL_REQUESTED").contains(parent.getStatus()))return;
        var children=pending(id);
        if("CANCEL_REQUESTED".equals(parent.getStatus()) || Boolean.TRUE.equals(parent.getCancelRequested())) {
            for(var child:children)if(!"CANCEL_REQUESTED".equals(child.getStatus()))taskService.cancel(child.getId());
            if(pending(id).isEmpty())complete(parent,row,"CANCELLED");else yieldRun(parent,row);
            return;
        }
        if("PAUSE_REQUESTED".equals(parent.getStatus()) || Boolean.TRUE.equals(parent.getPauseRequested())) {
            for(var child:children)if(Set.of("RUNNING","QUEUED").contains(child.getStatus()))taskService.requestPause(child.getId());
            boolean quiet=childrenQuiet(id);
            if(quiet)complete(parent,row,"PAUSED");else yieldRun(parent,row);
            return;
        }
        if(!children.isEmpty()) {
            if(children.stream().anyMatch(child->"PAUSED".equals(child.getStatus()))) {
                parent.setStatus("PAUSE_REQUESTED");yieldRun(parent,row);return;
            }
            for(var child:children)if("CREATED".equals(child.getStatus()))execution.queueSystem(child.getId());
            yieldRun(parent,row);return;
        }
        var step=materialization.materializeNext(id,executionId);
        if(step.childTaskId()!=null) {
            execution.queueSystem(step.childTaskId());yieldRun(parent,row);return;
        }
        if(!step.progress().scanComplete()){yieldRun(parent,row);return;}
        var counts=tasks.bulkChildCounts(id);
        boolean failures=counts.stream().anyMatch(value->Set.of("FAILED","PARTIAL_FAILED","CANCELLED").contains(value.status())&&value.countValue()>0);
        boolean success=counts.stream().anyMatch(value->Set.of("SUCCEEDED","PARTIAL_FAILED").contains(value.status())&&value.countValue()>0);
        complete(parent,row,failures?(success?"PARTIAL_FAILED":"FAILED"):"SUCCEEDED");
    }

    /** A recorded stop may always withdraw this parent's children, even after its create grant is revoked.
     * This background-only path cannot select new data, enqueue work, or export any source content. */
    @Transactional
    public boolean controlIfRequested(String id,String executionId) {
        ScheduledActorAccessService.requireBackground();
        var parent=lock(id);
        if(!java.util.Objects.equals(executionId,parent.getCurrentExecutionId()))return false;
        boolean cancel="CANCEL_REQUESTED".equals(parent.getStatus());
        if(!cancel && !"PAUSE_REQUESTED".equals(parent.getStatus()))return false;
        var row=run(parent);
        if(row==null || !Set.of("QUEUED","RUNNING").contains(row.getStatus()))return true;
        for(var reference:pending(id))withdrawChild(parent,reference.getId(),cancel);
        var remaining=pending(id);
        boolean quiet=cancel?remaining.isEmpty():childrenQuiet(id);
        if(quiet)complete(parent,row,cancel?"CANCELLED":"PAUSED");else yieldRun(parent,row);
        return true;
    }

    /** Failure parks the original cursor and withdraws outstanding work before reporting a paused parent. */
    @Transactional
    public void parkAfterFailure(String id,String executionId) {
        ScheduledActorAccessService.requireBackground();
        var parent=lock(id);
        if(!java.util.Objects.equals(executionId,parent.getCurrentExecutionId())
                || !Set.of("QUEUED","RUNNING","PAUSE_REQUESTED","CANCEL_REQUESTED").contains(parent.getStatus()))return;
        var row=run(parent);if(row==null)return;
        if(!"CANCEL_REQUESTED".equals(parent.getStatus())) {
            parent.setStatus("PAUSE_REQUESTED");parent.setPauseRequested(true);
        }
        parent.setUpdatedTime(new Date());row.setStatus("QUEUED");
        row.setErrorMessage("批次已停止推进，请核对当前授权、发布资产及持久检查点后恢复");
        requireChanged(tasks.updateById(parent));requireChanged(executions.updateById(row));
    }

    private void withdrawChild(InspectionTask parent,String childId,boolean cancel) {
        var child=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,childId)
                .select(InspectionTask::getId,InspectionTask::getTaskType,InspectionTask::getCreatedBy,InspectionTask::getBulkParentTaskId,
                        InspectionTask::getStatus,InspectionTask::getCurrentExecutionId).last("FOR UPDATE"));
        if(child==null || !"PIPELINE".equals(child.getTaskType()) || !parent.getId().equals(child.getBulkParentTaskId())
                || !java.util.Objects.equals(parent.getCreatedBy(),child.getCreatedBy()))
            throw IqcException.invalidState("批次停止操作的子任务归属无效");
        if(TERMINAL.contains(child.getStatus()))return;
        String status=child.getStatus();
        if(cancel) {
            if("CANCEL_REQUESTED".equals(status))return;
            status=Set.of("RUNNING","PAUSE_REQUESTED").contains(status)?"CANCEL_REQUESTED":"CANCELLED";
            child.setCancelRequested(true);child.setAutomaticRetryEnabled(false);child.setRetryNotBeforeUtc(null);
        } else {
            if(Set.of("CREATED","PAUSED","PAUSE_REQUESTED","CANCEL_REQUESTED").contains(status))return;
            if(!Set.of("RUNNING","QUEUED").contains(status))throw IqcException.invalidState("无法暂停当前分片状态");
            status="RUNNING".equals(status)?"PAUSE_REQUESTED":"PAUSED";child.setPauseRequested(true);
        }
        child.setStatus(status);
        var change=Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,childId)
                .eq(InspectionTask::getBulkParentTaskId,parent.getId()).set(InspectionTask::getStatus,status)
                .set(cancel,InspectionTask::getCancelRequested,true).set(!cancel,InspectionTask::getPauseRequested,true)
                .set(cancel,InspectionTask::getAutomaticRetryEnabled,false).set(cancel,InspectionTask::getRetryNotBeforeUtc,null);
        requireChanged(tasks.update(null,change));
        if(child.getCurrentExecutionId()!=null && Set.of("CANCELLED","PAUSED").contains(status)) {
            var executionRow=executions.selectOne(Wrappers.<TaskExecution>lambdaQuery().eq(TaskExecution::getId,child.getCurrentExecutionId())
                    .eq(TaskExecution::getTaskId,childId).last("FOR UPDATE"));
            if(executionRow==null || !Set.of("QUEUED","PAUSED").contains(executionRow.getStatus()))
                throw IqcException.invalidState("批次停止操作的子执行状态无效");
            executionRow.setStatus(status);
            if("CANCELLED".equals(status)&&executionRow.getCompletedAtUtc()==null)executionRow.setCompletedAtUtc(LocalDateTime.now(ZoneOffset.UTC));
            requireChanged(executions.updateById(executionRow));
        }
    }

    private InspectionTask lock(String id) {
        var value=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,id).last("FOR UPDATE"));
        materialization.definition(value);return value;
    }
    private void requireOwner(InspectionTask parent) {
        scope.requireScheduledResources(BulkTaskMaterializationService.GRANTS);
        scope.requireScheduledBound(materialization.definition(parent).ownerBound());
        if(!scope.canView(parent.getCreatedBy(),parent.getOwnerGroupId()))throw IqcException.accessDenied("无权操作批次");
    }
    private TaskExecution run(InspectionTask parent) {
        if(parent.getCurrentExecutionId()==null)return null;
        return executions.selectOne(Wrappers.<TaskExecution>lambdaQuery().eq(TaskExecution::getId,parent.getCurrentExecutionId())
                .eq(TaskExecution::getTaskId,parent.getId()).last("FOR UPDATE"));
    }
    private boolean childrenQuiet(String parentId) {
        return tasks.bulkChildCounts(parentId).stream().allMatch(value->value.countValue()==0
                || TERMINAL.contains(value.status()) || Set.of("CREATED","PAUSED").contains(value.status()));
    }
    private java.util.List<InspectionTask> pending(String parentId) {
        // At most one unfinished shard is normally created; bounded recovery also handles older partial fixtures.
        return tasks.selectList(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getBulkParentTaskId,parentId)
                .select(InspectionTask::getId,InspectionTask::getStatus,InspectionTask::getBulkShardNo)
                .notIn(InspectionTask::getStatus,TERMINAL).orderByAsc(InspectionTask::getBulkShardNo).last("LIMIT 8"));
    }
    private void yieldRun(InspectionTask parent,TaskExecution row) {
        if("RUNNING".equals(parent.getStatus()))parent.setStatus("QUEUED");
        parent.setUpdatedTime(new Date());row.setStatus("QUEUED");
        requireChanged(tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,parent.getId())
                .eq(InspectionTask::getCurrentExecutionId,row.getId()).set(InspectionTask::getStatus,parent.getStatus())
                .set(InspectionTask::getUpdatedTime,parent.getUpdatedTime())));
        requireChanged(executions.updateById(row));
    }
    private void complete(InspectionTask parent,TaskExecution row,String status) {
        parent.setStatus(status);parent.setUpdatedTime(new Date());row.setStatus(status);
        if(TERMINAL.contains(status)&&row.getCompletedAtUtc()==null)row.setCompletedAtUtc(LocalDateTime.now(ZoneOffset.UTC));
        requireChanged(tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,parent.getId())
                .eq(InspectionTask::getCurrentExecutionId,row.getId()).set(InspectionTask::getStatus,status)
                .set(InspectionTask::getUpdatedTime,parent.getUpdatedTime())));
        requireChanged(executions.updateById(row));
    }
    private static void requireChanged(int value) {
        if(value!=1)throw IqcException.invalidState("批次任务或执行状态保存失败");
    }
}
