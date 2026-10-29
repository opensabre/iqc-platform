package io.github.opensabre.iqc.schedule;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.dao.TriggerAttemptEventMapper;
import io.github.opensabre.iqc.schedule.model.TriggerAttemptEvent;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.InspectionTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;
import java.util.*;
@Service @RequiredArgsConstructor
public class TriggerAttemptService {
    public enum Kind { PERIODIC,SUBSCRIPTION,TASK }
    public enum FailureStage { AUTHORIZATION,EXPIRATION,MATERIALIZATION }
    public record Context(Kind kind,String parentId,String revisionId,String evidenceHash,Instant plannedAt,
                          ScheduledActorAccessService.CreationBound ownerBound) {
        public Context {
            if(kind==null || parentId==null || !parentId.matches("[A-Za-z0-9:_-]{1,64}")
                    || (revisionId!=null && !revisionId.matches("[A-Za-z0-9:_-]{1,64}"))
                    || evidenceHash==null || !evidenceHash.matches("[a-f0-9]{64}") || ownerBound==null
                    || !"iqc-schedule-owner-scope-v1".equals(ownerBound.schemaVersion()) || ownerBound.userId()==null
                    || !ownerBound.userId().matches("[A-Za-z0-9:_-]{1,64}") || ownerBound.owner()==null
                    || ownerBound.owner().isBlank() || ownerBound.owner().length()>128 || "system".equals(ownerBound.owner()))
                throw IqcException.invalidState("触发尝试来源证据无效");
        }
    }
    public record Handle(String attemptId,Context context,String contextHash) { }
    public record View(String attemptId,String triggerKind,String parentId,String revisionId,String evidenceHash,
                       java.time.LocalDateTime plannedAtUtc,java.time.LocalDateTime startedAtUtc,java.time.LocalDateTime finishedAtUtc,
                       String outcome,Integer processedUnits,String errorType,String failureStage,String errorCode) { }
    private final TriggerAttemptEventMapper events;
    private final ObjectMapper json;
    private final IqcDataScope scope;
    private final ScheduledActorAccessService actors;
    private final PeriodicScheduleService schedules;
    private final EventSubscriptionService subscriptions;
    private final InspectionTaskService tasks;
    private String write(Context context){try{return json.writeValueAsString(context);}catch(Exception failure){throw IqcException.invalidState("尝试来源序列化失败");}}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Handle begin(Context context){
        ScheduledActorAccessService.requireBackground();String body=write(context);
        if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw IqcException.invalidState("尝试来源证据超过8KiB");
        String id=UUID.randomUUID().toString().replace("-","");
        var handle=new Handle(id,context,SelectorAssetService.hash(body));var row=row(handle,"STARTED",body);
        insert(row);return handle;
    }
    private TriggerAttemptEvent row(Handle handle,String phase,String body){
        var context=handle.context();var row=new TriggerAttemptEvent();row.setId(handle.attemptId()+":"+("STARTED".equals(phase)?"S":"F"));
        row.setAttemptId(handle.attemptId());row.setPhase(phase);row.setTriggerKind(context.kind().name());row.setParentId(context.parentId());
        row.setRevisionId(context.revisionId());row.setEvidenceHash(context.evidenceHash());row.setOwnerUserId(context.ownerBound().userId());
        row.setContextHash(handle.contextHash());row.setContextJson(body);row.setPlannedAtUtc(context.plannedAt()==null?null:PeriodicScheduleService.utc(context.plannedAt()));
        row.setRecordedAtUtc(PeriodicScheduleService.utc(Instant.now()));row.setCreatedBy(context.ownerBound().owner());row.setUpdatedBy(context.ownerBound().owner());return row;
    }
    private void insert(TriggerAttemptEvent row){if(events.insert(row)!=1)throw IqcException.invalidState("触发尝试追加失败");}
    private void finish(Handle handle,String outcome,Integer units,String errorType,String stage,String code){
        if(handle==null || handle.context()==null || handle.attemptId()==null || !handle.attemptId().matches("[a-f0-9]{32}"))throw IqcException.invalidState("尝试句柄无效");
        String body=write(handle.context());if(!SelectorAssetService.hash(body).equals(handle.contextHash()))throw IqcException.invalidState("尝试上下文摘要无效");
        var start=events.selectOne(Wrappers.<TriggerAttemptEvent>lambdaQuery().eq(TriggerAttemptEvent::getAttemptId,handle.attemptId())
                .eq(TriggerAttemptEvent::getPhase,"STARTED").last("FOR UPDATE"));
        if(start==null || !Objects.equals(start.getContextHash(),handle.contextHash()) || !Objects.equals(start.getContextJson(),body)
                || !Objects.equals(start.getParentId(),handle.context().parentId()) || !Objects.equals(start.getRevisionId(),handle.context().revisionId())
                || !Objects.equals(start.getTriggerKind(),handle.context().kind().name()) || !Objects.equals(start.getEvidenceHash(),handle.context().evidenceHash())
                || !Objects.equals(start.getOwnerUserId(),handle.context().ownerBound().userId()) || !Objects.equals(start.getCreatedBy(),handle.context().ownerBound().owner()))
            throw IqcException.invalidState("尝试开始证据缺失或变化");
        var previous=events.selectOne(Wrappers.<TriggerAttemptEvent>lambdaQuery().eq(TriggerAttemptEvent::getAttemptId,handle.attemptId()).eq(TriggerAttemptEvent::getPhase,"FINISHED"));
        if(previous!=null){
            // A completion already committed is authoritative; failure handling must never replace it.
            if("FAILED".equals(outcome))return;
            if(!Objects.equals(outcome,previous.getOutcome()) || !Objects.equals(units,previous.getProcessedUnits()) || !Objects.equals(errorType,previous.getErrorType()))
                throw IqcException.invalidState("同一尝试已有不同完成事实");return;
        }
        var row=row(handle,"FINISHED",body);row.setOutcome(outcome);row.setProcessedUnits(units);row.setErrorType(errorType);row.setFailureStage(stage);row.setErrorCode(code);insert(row);
    }
    /** Must join the business transaction, so a positive completion cannot outlive a rollback. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void committed(Handle handle,int units){
        if(units<0 || units>50)throw IqcException.invalidArgument("尝试处理数量无效");
        finish(handle,units>0?"DOMAIN_COMMITTED":"NO_CHANGE",units,null,null,null);
    }
    /** Dispatcher calls this after the business transaction and actor scope have unwound. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failed(Handle handle,FailureStage stage,RuntimeException failure){
        ScheduledActorAccessService.requireBackground();
        String type=failure==null?"RuntimeException":failure.getClass().getSimpleName();
        if(!type.matches("[A-Za-z0-9_$]{1,128}"))type="RuntimeException";
        String code=null;
        if(failure instanceof io.github.opensabre.common.core.exception.BaseException domain && domain.getErrorType() instanceof io.github.opensabre.iqc.governance.IqcErrorType known)
            code=known.getCode();
        if(stage==null)throw IqcException.invalidArgument("缺少触发失败阶段");
        finish(handle,"FAILED",null,type,stage.name(),code);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public IqcPage<View> page(Kind kind,String parentId,long current,long size){
        if(kind==null || parentId==null || current<1 || size<1 || size>100)throw IqcException.invalidArgument("尝试查询参数无效");
        switch(kind){case PERIODIC->schedules.get(parentId);case SUBSCRIPTION->subscriptions.get(parentId);case TASK->tasks.get(parentId);}
        var query=Wrappers.<TriggerAttemptEvent>lambdaQuery().eq(TriggerAttemptEvent::getTriggerKind,kind.name()).eq(TriggerAttemptEvent::getParentId,parentId).eq(TriggerAttemptEvent::getPhase,"STARTED");
        // Attempt diagnostics include authorization failures; they stay private even if the parent is group-visible.
        if(!scope.canViewAll()){var caller=actors.capture();query.eq(TriggerAttemptEvent::getCreatedBy,caller.owner()).eq(TriggerAttemptEvent::getOwnerUserId,caller.userId());}
        var page=events.selectPage(new Page<>(current,size),query.orderByDesc(TriggerAttemptEvent::getRecordedAtUtc).orderByDesc(TriggerAttemptEvent::getId));
        var ids=page.getRecords().stream().map(TriggerAttemptEvent::getAttemptId).toList();
        var endings=new HashMap<String,TriggerAttemptEvent>();
        if(!ids.isEmpty())for(var end:events.selectList(Wrappers.<TriggerAttemptEvent>lambdaQuery().in(TriggerAttemptEvent::getAttemptId,ids).eq(TriggerAttemptEvent::getPhase,"FINISHED")))
            endings.put(end.getAttemptId(),end);
        var records=new ArrayList<View>();
        for(var start:page.getRecords()){
            if(!SelectorAssetService.hash(start.getContextJson()).equals(start.getContextHash()))throw IqcException.invalidState("尝试来源摘要无效");
            var end=endings.get(start.getAttemptId());
            if(end!=null && (!Objects.equals(start.getContextHash(),end.getContextHash()) || !Objects.equals(start.getContextJson(),end.getContextJson())
                    || end.getOutcome()==null || !Set.of("DOMAIN_COMMITTED","NO_CHANGE","FAILED").contains(end.getOutcome())))throw IqcException.invalidState("尝试完成来源无效");
            records.add(new View(start.getAttemptId(),start.getTriggerKind(),start.getParentId(),start.getRevisionId(),start.getEvidenceHash(),
                    start.getPlannedAtUtc(),start.getRecordedAtUtc(),end==null?null:end.getRecordedAtUtc(),end==null?"UNCONFIRMED":end.getOutcome(),
                    end==null?null:end.getProcessedUnits(),end==null?null:end.getErrorType(),end==null?null:end.getFailureStage(),end==null?null:end.getErrorCode()));
        }
        return new IqcPage<>(List.copyOf(records),page.getCurrent(),page.getSize(),page.getTotal());
    }
}
