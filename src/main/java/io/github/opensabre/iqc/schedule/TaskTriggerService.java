package io.github.opensabre.iqc.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.*;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.selector.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class TaskTriggerService {
    public record Accepted(String schemaVersion,TaskTriggerRequest request,String payloadHash,PipelineAssetService.Prepared pipeline,
            SelectorAssetService.Prepared selector,ScheduledActorAccessService.CreationBound ownerBound) { }
    public record Frame(String taskId,Accepted accepted,String requestHash) { }
    private final InspectionTaskMapper rows;
    private final ConversationMapper conversations;
    private final ConversationApiIngestService ingest;
    private final PipelineAssetService pipelines;
    private final SelectorAssetService selectors;
    private final ConversationSelectionService selections;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final InspectionTaskService tasks;
    private final InspectionExecutionService executions;
    private final ObjectMapper json;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private ObjectMapper exact(){return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw IqcException.invalidState("触发配置序列化失败");}}
    private <T>T read(String text,Class<T> type){try{return exact().readValue(text,type);}catch(Exception failure){throw IqcException.invalidArgument("触发协议或冻结配置无效");}}
    public static Set<String> grants(TaskTriggerRequest request){var grants=new HashSet<>(Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:trigger:receive"));
        if(request.content()!=null)grants.add("iqc:conversation:upload");return Set.copyOf(grants);}
    public InspectionTask receive(String requestJson){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for(int attempt=0;;attempt++) {
            try{return transaction.execute(status->receiveAttempt(requestJson));}
            catch(org.springframework.dao.PessimisticLockingFailureException | org.springframework.dao.DuplicateKeyException conflict){
                if(attempt>=2)throw conflict;
            }
        }
    }
    private InspectionTask receiveAttempt(String requestJson){
        if(requestJson==null || requestJson.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw IqcException.invalidArgument("触发请求超过16MiB");
        var request=read(requestJson,TaskTriggerRequest.class);String payloadHash=SelectorAssetService.hash(write(request));
        var bound=actors.capture();String id="t"+SelectorAssetService.hash(write(List.of(bound.userId(),request.source(),request.eventId()))).substring(0,63);
        var existing=rows.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getCreatedBy,bound.owner())
                .eq(InspectionTask::getTriggerSource,request.source()).eq(InspectionTask::getTriggerEventId,request.eventId()).last("FOR UPDATE"));
        if(existing!=null){
            var original=accepted(existing);if(!bound.userId().equals(original.ownerBound().userId()))throw IqcException.accessDenied("原接收身份不属于当前负责人");
            if(!bound.owner().equals(existing.getCreatedBy()) || !payloadHash.equals(existing.getTriggerPayloadHash()))
                throw IqcException.invalidState("相同触发身份对应不同载荷");return existing;}
        if(request.content()!=null)ingest.validate(request.content());
        if(request.occurredAt().isAfter(Instant.now().plusSeconds(5)) || !request.expiresAt().isAfter(Instant.now()))
            throw IqcException.invalidArgument("新触发事件时刻在未来或已过期");
        var pipeline=pipelines.publishedReleaseForTask(request.pipeline().pipelineId(),request.pipeline().versionNo());
        var selector=request.selector()==null?null:selectors.publishedForTask(request.selector().selectorId(),request.selector().versionNo());
        var accepted=new Accepted("iqc-trigger-accepted-v1",request,payloadHash,pipeline,selector,bound);
        var row=new InspectionTask();row.setId(id);row.setName(request.name().trim());row.setTaskType("PIPELINE");row.setStatus("WAITING_INPUT");
        row.setTriggerSource(request.source());row.setTriggerEventId(request.eventId());row.setTriggerPayloadHash(payloadHash);row.setTriggerRequestJson(write(accepted));row.setTriggerRequestHash(SelectorAssetService.hash(row.getTriggerRequestJson()));
        if(row.getTriggerRequestJson().getBytes(StandardCharsets.UTF_8).length>32*1024*1024)throw IqcException.invalidArgument("触发冻结配置超过32MiB");
        row.setConversationIdsJson("[]");row.setConcurrencyLimit(request.concurrency());row.setTotalMessages(0);row.setProcessedMessages(0);row.setFailedMessages(0);row.setAttemptCount(0);
        row.setRuleSnapshotJson(write(Map.of("triggerAccepted",Map.of("payloadHash",payloadHash,"eventType",request.eventType(),"occurredAt",request.occurredAt()))));
        // Unknown future input must stay private; known sources may share only their existing intersection.
        String group=scope.groupId(),pipelineGroup=pipelines.get(request.pipeline().pipelineId()).getOwnerGroupId();
        Conversation target=request.target()==null?null:target(request.target(),false);
        if(target!=null && !scope.canView(target.getCreatedBy(),target.getOwnerGroupId()))throw IqcException.accessDenied("无权使用事件目标会话");
        boolean sourceGroup=target!=null && Objects.equals(group,target.getOwnerGroupId());
        row.setOwnerGroupId(group!=null && group.equals(pipelineGroup) && (selector==null || group.equals(selector.ownerGroupId())) && sourceGroup?group:null);
        if(rows.insert(row)!=1)throw IqcException.invalidState("触发接收记录保存失败");return row;
    }
    private Accepted accepted(InspectionTask row){
        if(row.getTriggerRequestJson()==null || row.getTriggerRequestJson().length()>32*1024*1024
                || !SelectorAssetService.hash(row.getTriggerRequestJson()).equals(row.getTriggerRequestHash()))throw IqcException.invalidState("触发接收证据缺失");
        var accepted=read(row.getTriggerRequestJson(),Accepted.class);var request=accepted.request();
        if(!"iqc-trigger-accepted-v1".equals(accepted.schemaVersion()) || request==null || accepted.ownerBound()==null || accepted.pipeline()==null
                || !Objects.equals(row.getTriggerSource(),request.source()) || !Objects.equals(row.getTriggerEventId(),request.eventId())
                || !Objects.equals(row.getTriggerPayloadHash(),accepted.payloadHash()) || !SelectorAssetService.hash(write(request)).equals(accepted.payloadHash())
                || !Objects.equals(row.getCreatedBy(),accepted.ownerBound().owner())
                || !Objects.equals(request.pipeline().pipelineId(),accepted.pipeline().pipelineId()) || !Objects.equals(request.pipeline().versionNo(),accepted.pipeline().versionNo())
                || (request.selector()==null)!=(accepted.selector()==null)
                || (request.selector()!=null && (!request.selector().selectorId().equals(accepted.selector().selectorId()) || request.selector().versionNo()!=accepted.selector().versionNo())))
            throw IqcException.invalidState("触发接收来源或摘要无效");return accepted;
    }
    private Conversation target(TaskTriggerRequest.Target target,boolean lock){
        var query=Wrappers.<Conversation>lambdaQuery();if(target.conversationId()!=null)query.eq(Conversation::getId,target.conversationId());
        else query.eq(Conversation::getSourceType,target.sourceType()).eq(Conversation::getExternalId,target.externalId());
        query.last(lock?"LIMIT 2 FOR UPDATE":"LIMIT 2");var targets=conversations.selectList(query);
        if(targets.size()>1)throw new ConversationReplayConflictException("目标输入身份不唯一，请指定明确conversationId");
        return targets.isEmpty()?null:targets.getFirst();
    }
    public List<String> waiting(Instant now){ScheduledActorAccessService.requireBackground();return rows.selectList(Wrappers.<InspectionTask>lambdaQuery()
            .select(InspectionTask::getId).eq(InspectionTask::getStatus,"WAITING_INPUT").isNotNull(InspectionTask::getTriggerPayloadHash)
            .and(q->q.isNull(InspectionTask::getTriggerRetryNotBeforeUtc).or().le(InspectionTask::getTriggerRetryNotBeforeUtc,PeriodicScheduleService.utc(now)))
            .orderByAsc(InspectionTask::getCreatedTime).orderByAsc(InspectionTask::getId).last("LIMIT 25")).stream().map(InspectionTask::getId).toList();}
    public Frame peek(String id){ScheduledActorAccessService.requireBackground();var row=rows.selectById(id);
        if(row==null || !"WAITING_INPUT".equals(row.getStatus()))return null;return new Frame(id,accepted(row),row.getTriggerRequestHash());}
    @Transactional public boolean expire(Frame frame,Instant now){
        ScheduledActorAccessService.requireBackground();if(frame.accepted().request().expiresAt().isAfter(now))return false;
        return rows.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,frame.taskId()).eq(InspectionTask::getStatus,"WAITING_INPUT")
                .eq(InspectionTask::getTriggerPayloadHash,frame.accepted().payloadHash()).eq(InspectionTask::getTriggerRequestHash,frame.requestHash())
                .set(InspectionTask::getStatus,"TRIGGER_EXPIRED")
                .set(InspectionTask::getTriggerError,"输入等待已过期，重新提交需使用明确的新事件身份"))==1;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public boolean materialize(Frame frame,ScheduledActorAccessService.Resolved actor){
        var row=rows.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,frame.taskId()).last("FOR UPDATE"));
        if(row==null || !"WAITING_INPUT".equals(row.getStatus()) || !Objects.equals(row.getTriggerPayloadHash(),frame.accepted().payloadHash())
                || !Objects.equals(row.getTriggerRequestHash(),frame.requestHash()))return false;
        var accepted=accepted(row);var request=accepted.request();validateActor(accepted,actor);
        if(!request.expiresAt().isAfter(Instant.now())){terminal(row,"TRIGGER_EXPIRED","输入等待已过期");return true;}
        var pipeline=pipelines.publishedReleaseForTask(request.pipeline().pipelineId(),request.pipeline().versionNo());
        var selector=request.selector()==null?null:selectors.publishedForTask(request.selector().selectorId(),request.selector().versionNo());
        if(!SelectorAssetService.hash(write(pipeline)).equals(SelectorAssetService.hash(write(accepted.pipeline())))
                || (selector!=null && !SelectorAssetService.hash(write(selector)).equals(SelectorAssetService.hash(write(accepted.selector())))))
            throw IqcException.invalidState("触发冻结资产来源发生变化");
        Conversation conversation;
        try{conversation=request.content()!=null?ingest.ingest(request.content(),null):target(request.target(),false);}
        catch(ConversationReplayConflictException conflict){terminal(row,"TRIGGER_CONFLICT","提交正文或分析元数据与已有外部身份不一致，需新输入修订/事件身份");return true;}
        if(conversation==null || conversation.getMessageCount()==null || conversation.getMessageCount()<1
                || !Set.of("IMPORTED","IMPORTED_WITH_ERRORS").contains(conversation.getStatus())){deferLocked(row,"目标会话输入尚未就绪");return false;}
        if(!scope.canView(conversation.getCreatedBy(),conversation.getOwnerGroupId()))throw IqcException.accessDenied("当前授权无法使用目标会话");
        if(request.target()!=null && !request.target().inputRevision().equals(conversation.getSourceFingerprint())){
            terminal(row,"TRIGGER_CONFLICT","目标输入修订与接收请求不一致，需新事件身份");return true;}
        boolean asr=request.requireASR() || "ASRCompleted".equals(request.eventType());
        if(asr && !Set.of("COMPLETED","SUCCESS","SUCCEEDED").contains(conversation.getAsrStatus()==null?"":conversation.getAsrStatus())){
            deferLocked(row,"目标会话ASR尚未完成");return false;}
        var selection=selector==null?null:selections.materializeTargetsAt(selector.definition(),request.occurredAt(),List.of(conversation.getId()));
        var ids=selection==null?List.of(conversation.getId()):selection.selectedConversationIds();
        var evidence=json.createObjectNode().put("schemaVersion","iqc-trigger-materialization-v1").put("eventType",request.eventType())
                .put("conversationId",conversation.getId()).put("inputRevision",conversation.getSourceFingerprint()).put("filteredOut",ids.isEmpty());
        evidence.set("authorization",json.valueToTree(actor));var identity=new InspectionTaskService.TriggerIdentity(accepted.payloadHash(),request.occurredAt(),evidence);
        var materialized=tasks.materializeTriggeredPipelineTask(pipeline,row,ids,request.concurrency(),selector,selection,identity);
        validateActor(accepted,actor);if("CREATED".equals(materialized.getStatus()))executions.queueSystem(materialized.getId());return true;
    }
    private void validateActor(Accepted accepted,ScheduledActorAccessService.Resolved actor){Instant now=Instant.now();var bound=accepted.ownerBound();
        if(actor==null || actor.checkedAt()==null || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5))
                || !Objects.equals(actor.userId(),bound.userId()) || !Objects.equals(actor.owner(),bound.owner()) || !Objects.equals(scope.owner(),actor.owner())
                || scope.canViewAll()!=actor.all() || !Objects.equals(scope.groupId(),actor.groupId()) || !actor.resourceCodes().containsAll(grants(accepted.request())))
            throw IqcException.accessDenied("触发物化未取得有效的当前负责人授权");}
    private void terminal(InspectionTask row,String status,String error){if(rows.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,row.getId())
            .eq(InspectionTask::getStatus,"WAITING_INPUT").set(InspectionTask::getStatus,status).set(InspectionTask::getTriggerError,error))!=1)
        throw IqcException.invalidState("触发状态已变化");}
    private void deferLocked(InspectionTask row,String error){rows.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,row.getId())
            .eq(InspectionTask::getStatus,"WAITING_INPUT").set(InspectionTask::getTriggerError,error)
            .set(InspectionTask::getTriggerRetryNotBeforeUtc,PeriodicScheduleService.utc(Instant.now().plusSeconds(30))));}
    @Transactional public void defer(Frame frame){rows.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,frame.taskId())
            .eq(InspectionTask::getStatus,"WAITING_INPUT").eq(InspectionTask::getTriggerPayloadHash,frame.accepted().payloadHash())
            .eq(InspectionTask::getTriggerRequestHash,frame.requestHash()).set(InspectionTask::getTriggerError,"当前授权、资产或接入服务暂不可用；保留事件并等待重试")
            .set(InspectionTask::getTriggerRetryNotBeforeUtc,PeriodicScheduleService.utc(Instant.now().plusSeconds(30))));}
}
