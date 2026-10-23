package io.github.opensabre.iqc.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.selector.ConversationSelectionService;
import io.github.opensabre.iqc.schedule.dao.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.*;
import java.util.*;

@Service
public class PeriodicScheduleService {
    public record Published(String schemaVersion,PeriodicDefinition definition,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) PipelineAssetService.Prepared pipeline,
                            SelectorAssetService.Prepared selector,ScheduledActorAccessService.CreationBound ownerBound) { }
    public record Frame(String scheduleId,String revisionId,Instant dueAt,Published snapshot,String revisionHash) { }
    private final PeriodicScheduleMapper schedules;
    private final PeriodicScheduleRevisionMapper revisions;
    private final PipelineAssetService pipelines;
    private final SelectorAssetService selectors;
    private final ScheduledActorAccessService actors;
    private final ConversationSelectionService selections;
    private final InspectionTaskService tasks;
    private final InspectionTaskMapper taskRows;
    private final InspectionExecutionService executions;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.task.retry.AutomaticRetryService automaticRetries;
    private final io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskService rollouts;
    @org.springframework.beans.factory.annotation.Autowired
    public PeriodicScheduleService(PeriodicScheduleMapper schedules,PeriodicScheduleRevisionMapper revisions,PipelineAssetService pipelines,
            SelectorAssetService selectors,ScheduledActorAccessService actors,ConversationSelectionService selections,InspectionTaskService tasks,
            InspectionTaskMapper taskRows,InspectionExecutionService executions,IqcDataScope scope,ObjectMapper json,
            io.github.opensabre.iqc.task.retry.AutomaticRetryService automaticRetries,io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskService rollouts){
        this.schedules=schedules;this.revisions=revisions;this.pipelines=pipelines;this.selectors=selectors;this.actors=actors;
        this.selections=selections;this.tasks=tasks;this.taskRows=taskRows;this.executions=executions;this.scope=scope;this.json=json;
        this.automaticRetries=automaticRetries;this.rollouts=rollouts;
    }
    public PeriodicScheduleService(PeriodicScheduleMapper schedules,PeriodicScheduleRevisionMapper revisions,PipelineAssetService pipelines,
            SelectorAssetService selectors,ScheduledActorAccessService actors,ConversationSelectionService selections,InspectionTaskService tasks,
            InspectionTaskMapper taskRows,InspectionExecutionService executions,IqcDataScope scope,ObjectMapper json,
            io.github.opensabre.iqc.task.retry.AutomaticRetryService automaticRetries){
        this(schedules,revisions,pipelines,selectors,actors,selections,tasks,taskRows,executions,scope,json,automaticRetries,null);
    }
    private io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskService requireRollouts(){
        if(rollouts==null)throw IqcException.invalidState("周期灰度物化依赖不可用");return rollouts;
    }

    private ObjectMapper exact(){return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw IqcException.invalidArgument("周期配置无法序列化");}}
    private <T> T read(String value,Class<T> type){try{return exact().readValue(value,type);}catch(Exception e){throw IqcException.invalidState("周期快照无效");}}
    private void name(String value){if(value==null || value.isBlank() || value.length()>100)throw IqcException.invalidArgument("周期名称必须为1–100字");}
    private void revision(PeriodicSchedule row,int expected){if(row.getDraftRevision()==null || row.getDraftRevision()!=expected)throw IqcException.invalidState("周期草稿已变化，请重新加载");}
    private PeriodicSchedule require(String id,boolean lock){
        var query=Wrappers.<PeriodicSchedule>lambdaQuery().eq(PeriodicSchedule::getId,id);if(lock)query.last("FOR UPDATE");
        var row=schedules.selectOne(query);if(row==null)throw IqcException.notFound("周期定义不存在");
        if(!scope.canView(row.getCreatedBy(),row.getOwnerGroupId()))throw IqcException.accessDenied("无权查看周期定义");return row;
    }
    private void owner(PeriodicSchedule row){if(!scope.owner().equals(row.getCreatedBy()))throw IqcException.accessDenied("周期修订须由原负责人完成");}
    private void update(PeriodicSchedule row){if(schedules.updateById(row)!=1)throw IqcException.invalidState("周期定义更新失败");}
    private String referenceGroup(PeriodicDefinition definition){
        if(definition.usesRollout())requireRollouts().validateCurrentTarget(definition.targetPipelineId());
        else pipelines.publishedRelease(definition.pipeline().pipelineId(),definition.pipeline().versionNo());
        var selector=selectors.published(definition.selector().selectorId(),definition.selector().versionNo());
        String group=scope.groupId(),pipelineGroup=pipelines.get(definition.targetPipelineId()).getOwnerGroupId();
        return group!=null && group.equals(pipelineGroup) && group.equals(selector.ownerGroupId())?group:null;
    }
    public IqcPage<PeriodicSchedule> page(long current,long size){
        if(current<1 || size<1 || size>100)throw IqcException.invalidArgument("无效周期分页");
        var query=Wrappers.<PeriodicSchedule>lambdaQuery();if(!scope.canViewAll()){String group=scope.groupId();query.and(q->{
            q.eq(PeriodicSchedule::getCreatedBy,scope.owner());if(group!=null)q.or().eq(PeriodicSchedule::getOwnerGroupId,group);
        });}
        return IqcPage.from(schedules.selectPage(new Page<>(current,size),query.orderByDesc(PeriodicSchedule::getCreatedTime).orderByDesc(PeriodicSchedule::getId)));
    }
    public PeriodicSchedule get(String id){return require(id,false);}
    @Transactional public PeriodicSchedule create(String code,String name,PeriodicDefinition definition){
        if("system".equals(scope.owner()) || code==null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))throw IqcException.invalidArgument("周期编码或负责人无效");
        name(name);if(definition==null)throw IqcException.invalidArgument("缺少周期定义");
        var row=new PeriodicSchedule();row.setCode(code);row.setName(name.trim());row.setDraftRevision(1);row.setDraftConfigJson(write(definition));
        row.setStatus("DISABLED");row.setOwnerGroupId(referenceGroup(definition));
        if(schedules.insert(row)!=1)throw IqcException.invalidState("周期定义保存失败");return row;
    }
    @Transactional public PeriodicSchedule update(String id,int expected,String name,PeriodicDefinition definition){
        var row=require(id,true);owner(row);revision(row,expected);name(name);
        if(definition==null || expected==Integer.MAX_VALUE)throw IqcException.invalidArgument("周期定义或修订无效");
        row.setName(name.trim());row.setDraftConfigJson(write(definition));row.setDraftRevision(expected+1);
        // Old immutable revisions may contain private references; never broaden previously private history.
        String group=referenceGroup(definition);if(!Objects.equals(group,row.getOwnerGroupId()))row.setOwnerGroupId(null);
        update(row);return row;
    }
    public List<PeriodicScheduleRevision> history(String id){require(id,false);var rows=revisions.selectList(Wrappers.<PeriodicScheduleRevision>lambdaQuery()
            .eq(PeriodicScheduleRevision::getScheduleId,id).orderByDesc(PeriodicScheduleRevision::getVersionNo).last("LIMIT 100"));rows.forEach(this::snapshot);return rows;}
    private Published snapshot(PeriodicScheduleRevision version){
        if(version==null || version.getSnapshotJson()==null || version.getSnapshotJson().length()>16*1024*1024
                || !SelectorAssetService.hash(version.getSnapshotJson()).equals(version.getContentHash()))throw IqcException.invalidState("周期修订摘要无效");
        var value=read(version.getSnapshotJson(),Published.class);
        if(value==null||value.definition()==null||value.ownerBound()==null||value.selector()==null)throw IqcException.invalidState("周期发布来源缺失");
        boolean gray=value.definition().usesRollout();
        if(!(gray?"iqc-periodic-rollout-release-v1":"iqc-periodic-release-v1").equals(value.schemaVersion())
                ||(gray?value.pipeline()!=null:value.pipeline()==null
                    ||!value.definition().pipeline().pipelineId().equals(value.pipeline().pipelineId())
                    ||!Objects.equals(value.definition().pipeline().versionNo(),value.pipeline().versionNo()))
                || !value.definition().selector().selectorId().equals(value.selector().selectorId())
                || value.definition().selector().versionNo()!=value.selector().versionNo())throw IqcException.invalidState("周期发布来源无效");
        return value;
    }
    @Transactional public PeriodicScheduleRevision publish(String id,int expected){
        var row=require(id,true);owner(row);revision(row,expected);
        var previous=revisions.selectOne(Wrappers.<PeriodicScheduleRevision>lambdaQuery().eq(PeriodicScheduleRevision::getScheduleId,id)
                .eq(PeriodicScheduleRevision::getSourceDraftRevision,expected));if(previous!=null){snapshot(previous);return previous;}
        var definition=read(row.getDraftConfigJson(),PeriodicDefinition.class);var bound=actors.capture();
        if(!Objects.equals(bound.groupId(),row.getOwnerGroupId()))row.setOwnerGroupId(null);
        PipelineAssetService.Prepared pipeline=null;
        if(definition.usesRollout()){
            actors.resolveCurrent(bound,grants(definition));
            String group=referenceGroup(definition);if(!Objects.equals(group,row.getOwnerGroupId()))row.setOwnerGroupId(null);
        }else pipeline=pipelines.publishedReleaseForTask(definition.pipeline().pipelineId(),definition.pipeline().versionNo());
        var selector=selectors.publishedForTask(definition.selector().selectorId(),definition.selector().versionNo());
        Instant first=definition.first();if(first==null)throw IqcException.invalidArgument("周期生效范围内没有计划时刻");
        var last=revisions.selectOne(Wrappers.<PeriodicScheduleRevision>lambdaQuery().eq(PeriodicScheduleRevision::getScheduleId,id)
                .orderByDesc(PeriodicScheduleRevision::getVersionNo).last("LIMIT 1"));
        if(last!=null && last.getVersionNo()==Integer.MAX_VALUE)throw IqcException.invalidState("周期版本已达上限");
        var version=new PeriodicScheduleRevision();version.setScheduleId(id);version.setSourceDraftRevision(expected);
        version.setVersionNo(last==null?1:last.getVersionNo()+1);version.setSnapshotJson(write(new Published(definition.usesRollout()?"iqc-periodic-rollout-release-v1":"iqc-periodic-release-v1",definition,pipeline,selector,bound)));
        if(version.getSnapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024)throw IqcException.invalidArgument("周期发布快照超过16MiB");
        version.setContentHash(SelectorAssetService.hash(version.getSnapshotJson()));if(revisions.insert(version)!=1)throw IqcException.invalidState("周期修订保存失败");
        row.setActiveRevisionId(version.getId());row.setStatus("DISABLED");row.setNextPlannedAtUtc(utc(first));row.setRetryNotBeforeUtc(null);row.setLastError(null);update(row);return version;
    }
    @Transactional public PeriodicSchedule availability(String id,boolean enabled){
        var row=require(id,true);if(enabled && (row.getActiveRevisionId()==null || row.getNextPlannedAtUtc()==null))throw IqcException.invalidState("启用需要有后续计划的发布修订");
        row.setStatus(enabled?"ACTIVE":"DISABLED");row.setRetryNotBeforeUtc(null);update(row);return row;
    }
    public IqcPage<InspectionTask> instances(String id,long current,long size){
        require(id,false);if(current<1 || size<1 || size>100)throw IqcException.invalidArgument("无效周期实例分页");
        var query=Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getPeriodicScheduleId,id);
        if(!scope.canViewAll()){String group=scope.groupId();query.and(q->{q.eq(InspectionTask::getCreatedBy,scope.owner());if(group!=null)q.or().eq(InspectionTask::getOwnerGroupId,group);});}
        query.select(InspectionTask.class,field->!Set.of("ruleSnapshotJson","triggerRequestJson","retryPolicyJson").contains(field.getProperty()));
        return IqcPage.from(taskRows.selectPage(new Page<>(current,size),query.orderByDesc(InspectionTask::getPlannedAtUtc)));
    }
    public List<Instant> preview(PeriodicDefinition definition,Instant from,int size){if(definition==null)throw IqcException.invalidArgument("缺少周期定义");return definition.preview(from,size);}
    public List<String> due(Instant now){ScheduledActorAccessService.requireBackground();return schedules.selectList(Wrappers.<PeriodicSchedule>lambdaQuery()
            .eq(PeriodicSchedule::getStatus,"ACTIVE").le(PeriodicSchedule::getNextPlannedAtUtc,utc(now))
            .and(q->q.isNull(PeriodicSchedule::getRetryNotBeforeUtc).or().le(PeriodicSchedule::getRetryNotBeforeUtc,utc(now)))
            .orderByAsc(PeriodicSchedule::getNextPlannedAtUtc).orderByAsc(PeriodicSchedule::getId).last("LIMIT 25")).stream().map(PeriodicSchedule::getId).toList();}
    public Frame peek(String id,Instant now){
        ScheduledActorAccessService.requireBackground();var row=schedules.selectById(id);
        if(row==null || !"ACTIVE".equals(row.getStatus()) || row.getNextPlannedAtUtc()==null || instant(row.getNextPlannedAtUtc()).isAfter(now)
                || (row.getRetryNotBeforeUtc()!=null && instant(row.getRetryNotBeforeUtc()).isAfter(now)))return null;
        var version=revisions.selectById(row.getActiveRevisionId());if(version==null || !id.equals(version.getScheduleId()))throw IqcException.invalidState("周期发布指针无效");
        return new Frame(id,version.getId(),instant(row.getNextPlannedAtUtc()),snapshot(version),version.getContentHash());
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public boolean fire(Frame frame,Instant now,ScheduledActorAccessService.Resolved actor){
        var row=schedules.selectOne(Wrappers.<PeriodicSchedule>lambdaQuery().eq(PeriodicSchedule::getId,frame.scheduleId()).last("FOR UPDATE"));
        if(row==null || !"ACTIVE".equals(row.getStatus()) || !frame.revisionId().equals(row.getActiveRevisionId())
                || !utc(frame.dueAt()).equals(row.getNextPlannedAtUtc()))return false;
        var version=revisions.selectById(row.getActiveRevisionId());var release=snapshot(version);
        if(!frame.revisionHash().equals(version.getContentHash()) || !scope.owner().equals(release.ownerBound().owner()))throw IqcException.invalidState("周期触发来源已变化");
        validateActor(release,actor);
        var tick=release.definition().tick(frame.dueAt(),now);
        var existing=taskRows.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getPeriodicScheduleId,row.getId())
                .eq(InspectionTask::getPeriodicRevisionId,version.getId()).eq(InspectionTask::getPlannedAtUtc,utc(tick.plannedAt())));
        if(existing==null) {
            var evidence=json.createObjectNode().put("schemaVersion","iqc-periodic-trigger-v1").put("revisionHash",version.getContentHash())
                    .put("policy",release.definition().misfirePolicy().name()).put("missedFrom",tick.missedFrom()==null?null:tick.missedFrom().toString())
                    .put("missedUntil",tick.missedUntil()==null?null:tick.missedUntil().toString());
            evidence.set("authorization",json.valueToTree(actor));
            var identity=new InspectionTaskService.PeriodicIdentity(row.getId(),version.getId(),tick.plannedAt(),evidence);
            if(tick.skip()) {
                var task=new InspectionTask();task.setName(row.getName());task.setTaskType("PERIODIC_INSTANCE");task.setStatus("SKIPPED_MISFIRE");
                task.setPeriodicScheduleId(row.getId());task.setPeriodicRevisionId(version.getId());task.setPlannedAtUtc(utc(tick.plannedAt()));
                task.setConversationIdsJson("[]");task.setTotalMessages(0);task.setProcessedMessages(0);task.setFailedMessages(0);task.setAttemptCount(0);
                task.setRuleSnapshotJson(write(Map.of("periodicSnapshot",identity)));task.setOwnerGroupId(Objects.equals(scope.groupId(),row.getOwnerGroupId())?row.getOwnerGroupId():null);
                if(taskRows.insert(task)!=1)throw IqcException.invalidState("周期跳过证据保存失败");
            } else {
                var definition=release.definition();
                if(definition.usesRollout()){
                    existing=requireRollouts().createPeriodicWithSelector(definition.targetPipelineId(),row.getName(),release.selector(),identity,definition.concurrency());
                }else{
                    var pipeline=pipelines.publishedReleaseForTask(definition.pipeline().pipelineId(),definition.pipeline().versionNo());
                    var selector=selectors.publishedForTask(definition.selector().selectorId(),definition.selector().versionNo());
                    if(!SelectorAssetService.hash(write(pipeline)).equals(SelectorAssetService.hash(write(release.pipeline())))
                            || !SelectorAssetService.hash(write(selector)).equals(SelectorAssetService.hash(write(release.selector()))))throw IqcException.invalidState("周期资产冻结来源不一致");
                    var selection=selections.materializeAt(selector.definition(),tick.plannedAt());
                    existing=tasks.createPeriodicPipelineTask(pipeline,row.getName(),selector,selection,identity,definition.concurrency());
                }
                // The immutable schedule includes both asset sources and the original actor bound.
                // Sharing an instance cannot exceed sharing of its parent release.
                if(existing.getOwnerGroupId()!=null && !existing.getOwnerGroupId().equals(row.getOwnerGroupId())) {
                    if(taskRows.update(null,Wrappers.<InspectionTask>lambdaUpdate().eq(InspectionTask::getId,existing.getId())
                            .set(InspectionTask::getOwnerGroupId,null))!=1)throw IqcException.invalidState("周期实例范围保存失败");
                    existing.setOwnerGroupId(null);
                }
            }
        }
        validateActor(release,actor);
        if(existing!=null && "CREATED".equals(existing.getStatus())){
            if(release.definition().retryPolicy()!=null)automaticRetries.freeze(existing.getId(),release.definition().retryPolicy(),release.ownerBound(),grants(release.definition()));
            executions.queueSystem(existing.getId());
        }
        row.setNextPlannedAtUtc(tick.nextAt()==null?null:utc(tick.nextAt()));if(tick.nextAt()==null)row.setStatus("DISABLED");
        row.setLastError(null);row.setRetryNotBeforeUtc(null);update(row);return true;
    }
    public static Set<String> grants(PeriodicDefinition definition){
        var required=new HashSet<>(Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:schedule:publish"));
        if(definition.retryPolicy()!=null)required.add("iqc:task:retry-policy:configure");
        if(definition.usesRollout())required.add("iqc:pipeline:rollout:task:create");return Set.copyOf(required);
    }
    private void validateActor(Published release,ScheduledActorAccessService.Resolved actor) {
        var bound=release.ownerBound();Instant now=Instant.now();
        if(actor==null || actor.checkedAt()==null || actor.checkedAt().isBefore(now.minusSeconds(30))
                || actor.checkedAt().isAfter(now.plusSeconds(5)) || !bound.userId().equals(actor.userId())
                || !bound.owner().equals(actor.owner()) || !scope.owner().equals(actor.owner())
                || scope.canViewAll()!=actor.all() || !Objects.equals(scope.groupId(),actor.groupId())
                || actor.resourceCodes()==null || !actor.resourceCodes().containsAll(grants(release.definition())))
            throw IqcException.accessDenied("周期事务未取得有效且未过期的负责人授权");
    }
    @Transactional public void defer(Frame frame,Instant now){
        schedules.update(null,Wrappers.<PeriodicSchedule>lambdaUpdate().eq(PeriodicSchedule::getId,frame.scheduleId())
                .eq(PeriodicSchedule::getActiveRevisionId,frame.revisionId()).eq(PeriodicSchedule::getNextPlannedAtUtc,utc(frame.dueAt()))
                .set(PeriodicSchedule::getLastError,"本轮未取得有效授权或无法完成选数/入队，已保留计划时刻；请核对负责人权限、资产可用性及服务日志")
                .set(PeriodicSchedule::getRetryNotBeforeUtc,utc(now.plusSeconds(30))));
    }
    public static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    public static Instant instant(LocalDateTime value){return value.toInstant(ZoneOffset.UTC);}
}
