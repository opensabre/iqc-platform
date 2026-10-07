package io.github.opensabre.iqc.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.schedule.dao.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class EventSubscriptionService {
    public record Published(String schemaVersion,String name,EventSubscriptionDefinition definition,PipelineAssetService.Prepared pipeline,
                            SelectorAssetService.Prepared selector,ScheduledActorAccessService.CreationBound ownerBound) { }
    public record Frame(String subscriptionId,String revisionId,Published snapshot,String revisionHash) { }
    private final EventSubscriptionMapper schedules;
    private final EventSubscriptionRevisionMapper revisions;
    private final PipelineAssetService pipelines;
    private final SelectorAssetService selectors;
    private final ScheduledActorAccessService actors;
    private final io.github.opensabre.iqc.conversation.dao.ConversationMapper conversations;
    private final TaskTriggerService triggers;
    private final InspectionTaskMapper taskRows;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    private ObjectMapper exact(){return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw IqcException.invalidArgument("事件订阅配置无法序列化");}}
    private <T> T read(String value,Class<T> type){try{return exact().readValue(value,type);}catch(Exception e){throw IqcException.invalidState("事件订阅快照无效");}}
    private void name(String value){if(value==null || value.isBlank() || value.length()>100)throw IqcException.invalidArgument("事件订阅名称必须为1–100字");}
    private void revision(EventSubscription row,int expected){if(row.getDraftRevision()==null || row.getDraftRevision()!=expected)throw IqcException.invalidState("事件订阅草稿已变化，请重新加载");}
    private EventSubscription require(String id,boolean lock){
        var query=Wrappers.<EventSubscription>lambdaQuery().eq(EventSubscription::getId,id);if(lock)query.last("FOR UPDATE");
        var row=schedules.selectOne(query);if(row==null)throw IqcException.notFound("事件订阅定义不存在");
        if(!scope.canView(row.getCreatedBy(),row.getOwnerGroupId()))throw IqcException.accessDenied("无权查看事件订阅定义");return row;
    }
    private void owner(EventSubscription row){
        if(!scope.owner().equals(row.getCreatedBy()))throw IqcException.accessDenied("事件订阅修订须由原负责人完成");
        if(row.getActiveRevisionId()!=null){var original=snapshot(revisions.selectById(row.getActiveRevisionId()));
            if(!Objects.equals(original.ownerBound().userId(),actors.capture().userId()))
                throw IqcException.accessDenied("事件订阅负责人稳定身份已变化，不能自动移交历史订阅");}
    }
    private void update(EventSubscription row){if(schedules.updateById(row)!=1)throw IqcException.invalidState("事件订阅定义更新失败");}
    private String referenceGroup(EventSubscriptionDefinition definition){
        pipelines.publishedRelease(definition.pipeline().pipelineId(),definition.pipeline().versionNo());
        var selector=definition.selector()==null?null:selectors.published(definition.selector().selectorId(),definition.selector().versionNo());
        String group=scope.groupId(),pipelineGroup=pipelines.get(definition.pipeline().pipelineId()).getOwnerGroupId();
        return group!=null && group.equals(pipelineGroup) && (selector==null || group.equals(selector.ownerGroupId()))?group:null;
    }
    public IqcPage<EventSubscription> page(long current,long size){
        if(current<1 || size<1 || size>100)throw IqcException.invalidArgument("无效事件订阅分页");
        var query=Wrappers.<EventSubscription>lambdaQuery();if(!scope.canViewAll()){String group=scope.groupId();query.and(q->{
            q.eq(EventSubscription::getCreatedBy,scope.owner());if(group!=null)q.or().eq(EventSubscription::getOwnerGroupId,group);
        });}
        return IqcPage.from(schedules.selectPage(new Page<>(current,size),query.orderByDesc(EventSubscription::getCreatedTime).orderByDesc(EventSubscription::getId)));
    }
    public EventSubscription get(String id){return require(id,false);}
    @Transactional public EventSubscription create(String code,String name,EventSubscriptionDefinition definition){
        if("system".equals(scope.owner()) || code==null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))throw IqcException.invalidArgument("事件订阅编码或负责人无效");
        name(name);if(definition==null)throw IqcException.invalidArgument("缺少事件订阅定义");
        var row=new EventSubscription();row.setCode(code);row.setName(name.trim());row.setDraftRevision(1);row.setDraftConfigJson(write(definition));
        row.setStatus("DISABLED");row.setOwnerGroupId(referenceGroup(definition));
        if(schedules.insert(row)!=1)throw IqcException.invalidState("事件订阅定义保存失败");return row;
    }
    @Transactional public EventSubscription update(String id,int expected,String name,EventSubscriptionDefinition definition){
        var row=require(id,true);owner(row);revision(row,expected);name(name);
        if(definition==null || expected==Integer.MAX_VALUE)throw IqcException.invalidArgument("事件订阅定义或修订无效");
        row.setName(name.trim());row.setDraftConfigJson(write(definition));row.setDraftRevision(expected+1);
        // Old immutable revisions may contain private references; never broaden previously private history.
        String group=referenceGroup(definition);if(!Objects.equals(group,row.getOwnerGroupId()))row.setOwnerGroupId(null);
        update(row);return row;
    }
    public List<EventSubscriptionRevision> history(String id){require(id,false);var rows=revisions.selectList(Wrappers.<EventSubscriptionRevision>lambdaQuery()
            .eq(EventSubscriptionRevision::getSubscriptionId,id).orderByDesc(EventSubscriptionRevision::getVersionNo).last("LIMIT 100"));rows.forEach(this::snapshot);return rows;}
    private Published snapshot(EventSubscriptionRevision version){
        if(version==null || version.getSnapshotJson()==null || version.getSnapshotJson().length()>16*1024*1024
                || !SelectorAssetService.hash(version.getSnapshotJson()).equals(version.getContentHash()))throw IqcException.invalidState("事件订阅修订摘要无效");
        var value=read(version.getSnapshotJson(),Published.class);
        if(!"iqc-event-subscription-release-v1".equals(value.schemaVersion()) || value.name()==null || value.name().isBlank() || value.name().length()>100 || value.definition()==null || value.ownerBound()==null || value.pipeline()==null
                || !Objects.equals(version.getCreatedBy(),value.ownerBound().owner())
                || !value.definition().pipeline().pipelineId().equals(value.pipeline().pipelineId())
                || !Objects.equals(value.definition().pipeline().versionNo(),value.pipeline().versionNo())
                || (value.definition().selector()==null)!=(value.selector()==null)
                || (value.selector()!=null && (!value.definition().selector().selectorId().equals(value.selector().selectorId())
                || value.definition().selector().versionNo()!=value.selector().versionNo())))throw IqcException.invalidState("事件订阅发布来源无效");
        return value;
    }
    @Transactional public EventSubscriptionRevision publish(String id,int expected){
        var row=require(id,true);owner(row);revision(row,expected);
        var previous=revisions.selectOne(Wrappers.<EventSubscriptionRevision>lambdaQuery().eq(EventSubscriptionRevision::getSubscriptionId,id)
                .eq(EventSubscriptionRevision::getSourceDraftRevision,expected));if(previous!=null){snapshot(previous);return previous;}
        var definition=read(row.getDraftConfigJson(),EventSubscriptionDefinition.class);var bound=actors.capture();
        if(!Objects.equals(bound.groupId(),row.getOwnerGroupId()))row.setOwnerGroupId(null);
        var pipeline=pipelines.publishedReleaseForTask(definition.pipeline().pipelineId(),definition.pipeline().versionNo());
        var selector=definition.selector()==null?null:selectors.publishedForTask(definition.selector().selectorId(),definition.selector().versionNo());
        var last=revisions.selectOne(Wrappers.<EventSubscriptionRevision>lambdaQuery().eq(EventSubscriptionRevision::getSubscriptionId,id)
                .orderByDesc(EventSubscriptionRevision::getVersionNo).last("LIMIT 1"));
        if(last!=null && last.getVersionNo()==Integer.MAX_VALUE)throw IqcException.invalidState("事件订阅版本已达上限");
        var version=new EventSubscriptionRevision();version.setSubscriptionId(id);version.setSourceDraftRevision(expected);
        version.setVersionNo(last==null?1:last.getVersionNo()+1);version.setSnapshotJson(write(new Published("iqc-event-subscription-release-v1",row.getName(),definition,pipeline,selector,bound)));
        if(version.getSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw IqcException.invalidArgument("事件订阅发布快照超过16MiB");
        version.setContentHash(SelectorAssetService.hash(version.getSnapshotJson()));if(revisions.insert(version)!=1)throw IqcException.invalidState("事件订阅修订保存失败");
        row.setActiveRevisionId(version.getId());row.setStatus("DISABLED");row.setLastScannedAtUtc(null);row.setRetryNotBeforeUtc(null);row.setLastError(null);update(row);return version;
    }
    @Transactional public EventSubscription availability(String id,boolean enabled){
        var row=require(id,true);if(enabled && (row.getActiveRevisionId()==null))throw IqcException.invalidState("启用需要已发布修订");
        row.setStatus(enabled?"ACTIVE":"DISABLED");row.setRetryNotBeforeUtc(null);update(row);return row;
    }
    public IqcPage<InspectionTask> instances(String id,long current,long size){
        require(id,false);if(current<1 || size<1 || size>100)throw IqcException.invalidArgument("无效事件订阅实例分页");
        var query=Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getEventSubscriptionId,id);
        if(!scope.canViewAll()){String group=scope.groupId();query.and(q->{q.eq(InspectionTask::getCreatedBy,scope.owner());if(group!=null)q.or().eq(InspectionTask::getOwnerGroupId,group);});}
        query.select(InspectionTask.class,field->!Set.of("ruleSnapshotJson","triggerRequestJson","retryPolicyJson").contains(field.getProperty()));
        return IqcPage.from(taskRows.selectPage(new Page<>(current,size),query.orderByDesc(InspectionTask::getCreatedTime).orderByDesc(InspectionTask::getId)));
    }
    public static Set<String> grants(){return Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:trigger:receive","iqc:subscription:publish");}
    public static Set<String> grants(EventSubscriptionDefinition definition){
        var required=new HashSet<>(grants());if(definition.retryPolicy()!=null)required.add("iqc:task:retry-policy:configure");return Set.copyOf(required);
    }
    public List<String> due(Instant now){
        ScheduledActorAccessService.requireBackground();
        return schedules.selectList(Wrappers.<EventSubscription>lambdaQuery().select(EventSubscription::getId)
                .eq(EventSubscription::getStatus,"ACTIVE")
                .and(q->q.isNull(EventSubscription::getRetryNotBeforeUtc).or().le(EventSubscription::getRetryNotBeforeUtc,utc(now)))
                .orderByAsc(EventSubscription::getLastScannedAtUtc).orderByAsc(EventSubscription::getId).last("LIMIT 25"))
                .stream().map(EventSubscription::getId).toList();
    }
    public Frame peek(String id,Instant now){
        ScheduledActorAccessService.requireBackground();var row=schedules.selectById(id);
        if(row==null || !"ACTIVE".equals(row.getStatus()) || (row.getRetryNotBeforeUtc()!=null && instant(row.getRetryNotBeforeUtc()).isAfter(now)))return null;
        var version=revisions.selectById(row.getActiveRevisionId());
        if(version==null || !id.equals(version.getSubscriptionId()))throw IqcException.invalidState("事件订阅发布指针无效");
        var release=snapshot(version);
        if(!Objects.equals(row.getCreatedBy(),release.ownerBound().owner()))throw IqcException.invalidState("事件订阅负责人证据无效");
        return new Frame(id,version.getId(),release,version.getContentHash());
    }
    /** Byte-length framed UTF-8 tuple; exactly mirrored by the parameterized SQL anti-join. */
    public static String eventId(String revisionId,String conversationId,String fingerprint){
        StringBuilder framed=new StringBuilder();
        for(String value:List.of(revisionId,conversationId,fingerprint))framed.append(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(':').append(value);
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(framed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private void validateActor(Published release,ScheduledActorAccessService.Resolved actor){
        Instant now=Instant.now();var bound=release.ownerBound();
        if(actor==null || actor.checkedAt()==null || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5))
                || !Objects.equals(actor.userId(),bound.userId()) || !Objects.equals(actor.owner(),bound.owner()) || !Objects.equals(scope.owner(),actor.owner())
                || scope.canViewAll()!=actor.all() || !Objects.equals(scope.groupId(),actor.groupId()) || actor.resourceCodes()==null
                || !actor.resourceCodes().containsAll(grants(release.definition())))throw IqcException.accessDenied("订阅发现未取得有效的当前负责人授权");
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public int fire(Frame frame,ScheduledActorAccessService.Resolved actor){
        var row=schedules.selectOne(Wrappers.<EventSubscription>lambdaQuery().eq(EventSubscription::getId,frame.subscriptionId()).last("FOR UPDATE"));
        if(row==null || !"ACTIVE".equals(row.getStatus()) || !Objects.equals(row.getActiveRevisionId(),frame.revisionId())
                || (row.getRetryNotBeforeUtc()!=null && instant(row.getRetryNotBeforeUtc()).isAfter(Instant.now())))return 0;
        var version=revisions.selectById(row.getActiveRevisionId());var release=snapshot(version);
        if(!row.getId().equals(version.getSubscriptionId()) || !Objects.equals(frame.revisionHash(),version.getContentHash())
                || !Objects.equals(row.getCreatedBy(),release.ownerBound().owner()))throw IqcException.invalidState("订阅发布证据已变化");
        validateActor(release,actor);var definition=release.definition();
        var query=Wrappers.<io.github.opensabre.iqc.conversation.model.Conversation>lambdaQuery()
                .in(io.github.opensabre.iqc.conversation.model.Conversation::getStatus,List.of("IMPORTED","IMPORTED_WITH_ERRORS"))
                .gt(io.github.opensabre.iqc.conversation.model.Conversation::getMessageCount,0)
                .ge(io.github.opensabre.iqc.conversation.model.Conversation::getCreatedTime,java.util.Date.from(definition.effectiveFrom()))
                .le(io.github.opensabre.iqc.conversation.model.Conversation::getCreatedTime,java.util.Date.from(Instant.now()));
        if(definition.effectiveUntil()!=null)query.lt(io.github.opensabre.iqc.conversation.model.Conversation::getCreatedTime,java.util.Date.from(definition.effectiveUntil()));
        if("ASRCompleted".equals(definition.eventType()))query.in(io.github.opensabre.iqc.conversation.model.Conversation::getAsrStatus,List.of("COMPLETED","SUCCESS","SUCCEEDED"));
        if(!scope.canViewAll()){String group=scope.groupId();query.and(q->{
            q.eq(io.github.opensabre.iqc.conversation.model.Conversation::getCreatedBy,scope.owner());
            if(group!=null)q.or().eq(io.github.opensabre.iqc.conversation.model.Conversation::getOwnerGroupId,group);
        });}
        // A malformed legacy archive cannot block all later valid events. Diagnose only in service logs.
        var invalid=query.clone().and(q->q.isNull(io.github.opensabre.iqc.conversation.model.Conversation::getSourceFingerprint)
                .or().apply("NOT REGEXP_LIKE(source_fingerprint, '^[a-f0-9]{64}$', 'c')"))
                .select(io.github.opensabre.iqc.conversation.model.Conversation::getId).last("LIMIT 1");
        if(!conversations.selectList(invalid).isEmpty())log.warn("event=iqc_subscription_invalid_archive subscriptionId={}",row.getId());
        query.apply("REGEXP_LIKE(source_fingerprint, '^[a-f0-9]{64}$', 'c')");
        // Values are bound parameters. No user expression becomes SQL. Every receipt status counts, including DELETED.
        query.notExists("SELECT 1 FROM iqc_inspection_task t WHERE t.created_by={0} AND t.trigger_source='archive-subscription' "
                +"AND t.trigger_event_id=SHA2(CONCAT(OCTET_LENGTH({1}),':',{1},OCTET_LENGTH(iqc_conversation.id),':',iqc_conversation.id,"
                +"OCTET_LENGTH(iqc_conversation.source_fingerprint),':',iqc_conversation.source_fingerprint),256)",release.ownerBound().owner(),version.getId());
        query.orderByAsc(io.github.opensabre.iqc.conversation.model.Conversation::getCreatedTime)
                .orderByAsc(io.github.opensabre.iqc.conversation.model.Conversation::getId).last("LIMIT 2");
        int count=0;
        for(var conversation:conversations.selectList(query)){
            validateActor(release,actor);
            if(conversation.getCreatedTime()==null || conversation.getSourceFingerprint()==null || !conversation.getSourceFingerprint().matches("[a-f0-9]{64}"))
                throw IqcException.invalidState("归档事件时间或输入修订无效");
            var request=new TaskTriggerRequest("iqc-task-trigger-v1","archive-subscription",eventId(version.getId(),conversation.getId(),conversation.getSourceFingerprint()),
                    definition.eventType(),conversation.getCreatedTime().toInstant(),Instant.now().plusSeconds(definition.waitSeconds()),release.name(),
                    definition.pipeline(),definition.selector(),new TaskTriggerRequest.Target(conversation.getId(),null,null,conversation.getSourceFingerprint()),
                    null,definition.concurrency(),"ASRCompleted".equals(definition.eventType()),definition.retryPolicy());
            var receipt=triggers.receiveSubscription(request,release.ownerBound(),actor,release.pipeline(),release.selector(),row.getId(),version.getId());
            if(receipt.getOwnerGroupId()!=null && !Objects.equals(receipt.getOwnerGroupId(),row.getOwnerGroupId())){
                if(taskRows.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,receipt.getId()).set(InspectionTask::getOwnerGroupId,null))!=1)
                    throw IqcException.invalidState("订阅实例可见范围保存失败");
            }
            count++;
        }
        validateActor(release,actor);row.setLastScannedAtUtc(utc(Instant.now()));row.setRetryNotBeforeUtc(null);row.setLastError(null);update(row);return count;
    }
    /** A malformed release must not monopolize the first discovery page. No pointer or lifecycle change. */
    @Transactional public void deferUnreadable(String id,Instant now){
        ScheduledActorAccessService.requireBackground();
        schedules.update(null,Wrappers.<EventSubscription>lambdaUpdate().eq(EventSubscription::getId,id).eq(EventSubscription::getStatus,"ACTIVE")
                .set(EventSubscription::getLastScannedAtUtc,utc(now)).set(EventSubscription::getRetryNotBeforeUtc,utc(now.plusSeconds(30)))
                .set(EventSubscription::getLastError,"订阅发布证据暂不可读取；请核对修订及服务日志"));
    }
    @Transactional public void defer(Frame frame,Instant now){
        schedules.update(null,Wrappers.<EventSubscription>lambdaUpdate().eq(EventSubscription::getId,frame.subscriptionId())
                .eq(EventSubscription::getActiveRevisionId,frame.revisionId()).eq(EventSubscription::getStatus,"ACTIVE")
                .set(EventSubscription::getLastScannedAtUtc,utc(now)).set(EventSubscription::getRetryNotBeforeUtc,utc(now.plusSeconds(30)))
                .set(EventSubscription::getLastError,"当前授权、发布资产或归档接收暂不可用；保留订阅并等待重试"));
    }
    public static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    public static Instant instant(LocalDateTime value){return value.toInstant(ZoneOffset.UTC);}
}
