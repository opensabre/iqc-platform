package io.github.opensabre.iqc.task.retry;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.*;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.*;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service @RequiredArgsConstructor
public class AutomaticRetryService {
    public static final String CONFIGURE="iqc:task:retry-policy:configure";
    public record Envelope(String schemaVersion,String taskId,AutomaticRetryPolicy policy,
                           ScheduledActorAccessService.CreationBound ownerBound,Set<String> requiredGrants) { }
    public record View(AutomaticRetryPolicy policy,boolean enabled,LocalDateTime nextRetryAtUtc,String error,String policyHash) { }
    public record Frame(String taskId,String executionId,int attemptCount,Instant failureAt,String policyHash,
                        Envelope envelope,Instant retryAt) { }
    private static final Set<String> ALLOWED_GRANTS=Set.of(CONFIGURE,"iqc:pipeline:task:create","iqc:task:execute",
            "iqc:schedule:publish","iqc:subscription:publish","iqc:trigger:receive","iqc:conversation:upload");
    private final InspectionTaskMapper tasks;
    private final TaskExecutionMapper executions;
    private final ConversationMapper conversations;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private final ObjectProvider<InspectionTaskService> taskService;
    private final ObjectProvider<InspectionExecutionService> executionService;
    private final ObjectProvider<TriggerAttemptService> attempts;
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw IqcException.invalidState("重试策略序列化失败");}}
    private Envelope envelope(InspectionTask task){
        try{
            if(task.getRetryPolicyJson()==null || task.getRetryPolicyJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384
                    || !Objects.equals(SelectorAssetService.hash(task.getRetryPolicyJson()),task.getRetryPolicyHash()))
                throw IqcException.invalidState("重试策略摘要无效");
            var value=json.readValue(task.getRetryPolicyJson(),Envelope.class);
            if(!"iqc-automatic-retry-envelope-v1".equals(value.schemaVersion()) || !task.getId().equals(value.taskId())
                    || value.policy()==null || value.ownerBound()==null || !Objects.equals(task.getCreatedBy(),value.ownerBound().owner())
                    || !"iqc-schedule-owner-scope-v1".equals(value.ownerBound().schemaVersion())
                    || value.ownerBound().userId()==null || !value.ownerBound().userId().matches("[A-Za-z0-9:_-]{1,64}")
                    || value.requiredGrants()==null || !ALLOWED_GRANTS.containsAll(value.requiredGrants()) || !value.requiredGrants().containsAll(Set.of(CONFIGURE,"iqc:pipeline:task:create","iqc:task:execute")))
                throw IqcException.invalidState("重试策略来源无效");
            return value;
        }catch(IqcException error){throw error;}catch(Exception error){throw IqcException.invalidState("重试策略来源无法解析");}
    }
    private InspectionTask lock(String id){var task=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId,id).last("FOR UPDATE"));
        if(task==null)throw IqcException.notFound("任务不存在");return task;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void freeze(String taskId,AutomaticRetryPolicy policy,ScheduledActorAccessService.CreationBound bound,Set<String> serverRequiredGrants){
        if(policy==null)return;
        var task=lock(taskId);
        if(bound==null || !Objects.equals(bound.owner(),task.getCreatedBy()) || serverRequiredGrants==null || !ALLOWED_GRANTS.containsAll(serverRequiredGrants))
            throw IqcException.invalidState("重试负责人来源无效");
        var grants=new TreeSet<>(serverRequiredGrants);grants.addAll(Set.of(CONFIGURE,"iqc:pipeline:task:create","iqc:task:execute"));
        var value=new Envelope("iqc-automatic-retry-envelope-v1",taskId,policy,bound,Collections.unmodifiableSet(grants));
        String body=write(value),hash=SelectorAssetService.hash(body);
        if(task.getRetryPolicyHash()!=null){if(!hash.equals(task.getRetryPolicyHash()))throw IqcException.invalidState("冻结后不可替换自动重试策略");envelope(task);return;}
        if(!"PIPELINE".equals(task.getTaskType()) || !Set.of("CREATED","WAITING_INPUT").contains(task.getStatus())
                || task.getCurrentExecutionId()!=null || (task.getAttemptCount()!=null && task.getAttemptCount()!=0))
            throw IqcException.invalidState("只能在首次排队前冻结重试策略");
        boolean expired=policy.effectiveUntil()!=null && !policy.effectiveUntil().isAfter(Instant.now());
        task.setRetryPolicyJson(body);task.setRetryPolicyHash(hash);task.setAutomaticRetryEnabled(!expired);
        task.setRetryError(expired?"EFFECTIVE_UNTIL_REACHED":null);task.setRetryNotBeforeUtc(null);
        if(tasks.updateById(task)!=1)throw IqcException.invalidState("保存重试策略失败");
        envelope(task);
    }
    public View get(String id){return view(taskService.getObject().get(id));}
    private View view(InspectionTask task){return new View(task.getRetryPolicyHash()==null?null:envelope(task).policy(),
            Boolean.TRUE.equals(task.getAutomaticRetryEnabled()),task.getRetryNotBeforeUtc(),task.getRetryError(),task.getRetryPolicyHash());}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View configure(String id,AutomaticRetryPolicy policy){
        taskService.getObject().get(id);var task=lock(id);var bound=actors.capture();
        if(!Objects.equals(task.getCreatedBy(),bound.owner()))throw IqcException.accessDenied("只有原负责人可以配置重试");
        if(task.getRetryPolicyHash()!=null && !Objects.equals(envelope(task).ownerBound().userId(),bound.userId()))throw IqcException.accessDenied("负责人身份已变化");
        if(!"CREATED".equals(task.getStatus()) || !hasInputs(task))throw IqcException.invalidState("手动配置需要首次排队前的冻结生产任务");
        freeze(id,policy,bound,Set.of("iqc:pipeline:task:create","iqc:task:execute",CONFIGURE));return view(tasks.selectById(id));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View availability(String id,boolean enabled){
        taskService.getObject().get(id);var task=lock(id);var env=envelope(task);var caller=actors.capture();
        if(!Objects.equals(caller.owner(),env.ownerBound().owner()) || !Objects.equals(caller.userId(),env.ownerBound().userId()))
            throw IqcException.accessDenied("只有原稳定身份负责人可以启停重试");
        if(enabled && (Set.of("DELETED","CANCELLED","SUCCEEDED").contains(task.getStatus()) || exhausted(task,env.policy(),Instant.now())!=null))
            throw IqcException.invalidState("任务已终结或重试预算耗尽");
        task.setAutomaticRetryEnabled(enabled);if(tasks.updateById(task)!=1)throw IqcException.invalidState("启停重试失败");return view(task);
    }
    private boolean hasInputs(InspectionTask task){try{return json.readTree(task.getRuleSnapshotJson()).has("productionInputs");}catch(Exception error){return false;}}
    private static boolean failed(InspectionTask task){return Set.of("FAILED","PARTIAL_FAILED").contains(task.getStatus());}
    private static String exhausted(InspectionTask task,AutomaticRetryPolicy policy,Instant now){
        if(task.getAttemptCount()!=null && task.getAttemptCount()>=policy.maximumAttempts())return "MAXIMUM_ATTEMPTS_REACHED";
        if(policy.effectiveUntil()!=null && !now.isBefore(policy.effectiveUntil()))return "EFFECTIVE_UNTIL_REACHED";return null;}
    public List<String> waiting(Instant now){ScheduledActorAccessService.requireBackground();return tasks.selectList(Wrappers.<InspectionTask>lambdaQuery()
            .select(InspectionTask::getId).in(InspectionTask::getStatus,List.of("FAILED","PARTIAL_FAILED"))
            .eq(InspectionTask::getAutomaticRetryEnabled,true).isNotNull(InspectionTask::getRetryPolicyHash)
            .and(q->q.isNull(InspectionTask::getPauseRequested).or().eq(InspectionTask::getPauseRequested,false))
            .and(q->q.isNull(InspectionTask::getCancelRequested).or().eq(InspectionTask::getCancelRequested,false))
            .and(q->q.isNull(InspectionTask::getRetryNotBeforeUtc).or().le(InspectionTask::getRetryNotBeforeUtc,PeriodicScheduleService.utc(now)))
            .orderByAsc(InspectionTask::getUpdatedTime).orderByAsc(InspectionTask::getId).last("LIMIT 25")).stream().map(InspectionTask::getId).toList();}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Frame peek(String id,Instant now){
        ScheduledActorAccessService.requireBackground();var task=lock(id);
        if(!failed(task) || !Boolean.TRUE.equals(task.getAutomaticRetryEnabled()) || Boolean.TRUE.equals(task.getPauseRequested()) || Boolean.TRUE.equals(task.getCancelRequested()))return null;
        var env=envelope(task);String terminal=exhausted(task,env.policy(),now);
        if(terminal!=null){task.setAutomaticRetryEnabled(false);task.setRetryError(terminal);task.setRetryNotBeforeUtc(null);tasks.updateById(task);return null;}
        var execution=executions.selectById(task.getCurrentExecutionId());
        if(execution==null || !Objects.equals(execution.getTaskId(),id) || !Objects.equals(execution.getAttemptNo(),task.getAttemptCount())
                || !Set.of("FAILED","PARTIAL_FAILED").contains(execution.getStatus()) || execution.getUpdatedTime()==null
                || task.getAttemptCount()==null || task.getAttemptCount()<1 || !hasInputs(task))throw IqcException.invalidState("当前失败轮次证据无效");
        Instant failure=execution.getUpdatedTime().toInstant(),retryAt=failure.plusSeconds(env.policy().delaySeconds(task.getAttemptCount()));
        if(env.policy().effectiveUntil()!=null && !retryAt.isBefore(env.policy().effectiveUntil())){
            task.setAutomaticRetryEnabled(false);task.setRetryError("EFFECTIVE_UNTIL_BEFORE_NEXT_RETRY");task.setRetryNotBeforeUtc(null);tasks.updateById(task);return null;}
        if(now.isBefore(retryAt)){task.setRetryNotBeforeUtc(PeriodicScheduleService.utc(retryAt));task.setRetryError("WAITING_BACKOFF");tasks.updateById(task);return null;}
        return new Frame(id,task.getCurrentExecutionId(),task.getAttemptCount(),failure,task.getRetryPolicyHash(),env,retryAt);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public boolean fire(Frame frame,ScheduledActorAccessService.Resolved actor,TriggerAttemptService.Handle handle){
        if(frame==null || handle==null || !Objects.equals(handle.context(),new TriggerAttemptService.Context(
                TriggerAttemptService.Kind.TASK,frame.taskId(),frame.executionId(),frame.policyHash(),frame.retryAt(),frame.envelope().ownerBound())))
            throw IqcException.invalidState("重试尝试与执行来源不一致");
        var task=lock(frame.taskId());boolean queued=false;
        if(matches(task,frame) && Boolean.TRUE.equals(task.getAutomaticRetryEnabled()) && !Boolean.TRUE.equals(task.getPauseRequested()) && !Boolean.TRUE.equals(task.getCancelRequested())){
            var env=envelope(task);Instant now=Instant.now();
            if(exhausted(task,env.policy(),now)!=null){task.setAutomaticRetryEnabled(false);task.setRetryError(exhausted(task,env.policy(),now));task.setRetryNotBeforeUtc(null);tasks.updateById(task);}
            else if(!now.isBefore(frame.retryAt()) && (task.getRetryNotBeforeUtc()==null || !now.isBefore(task.getRetryNotBeforeUtc().toInstant(ZoneOffset.UTC)))){
                fresh(env,actor);
                var execution=executions.selectById(frame.executionId());
                if(execution==null || !Objects.equals(execution.getTaskId(),frame.taskId()) || !Objects.equals(execution.getAttemptNo(),frame.attemptCount())
                        || execution.getUpdatedTime()==null || !Objects.equals(execution.getUpdatedTime().toInstant(),frame.failureAt())
                        || !Set.of("FAILED","PARTIAL_FAILED").contains(execution.getStatus()))throw IqcException.invalidState("失败轮次证据已变化");
                if(!Objects.equals(frame.retryAt(),frame.failureAt().plusSeconds(env.policy().delaySeconds(frame.attemptCount()))))
                    throw IqcException.invalidState("重试退避证据不一致");
                try{var input=json.readTree(task.getRuleSnapshotJson()).path("productionInputs").path("conversations");
                    if(!input.isArray() || input.isEmpty() || input.size()>1000)throw IqcException.invalidState("生产输入来源缺失");
                    for(var value:input){var conversation=conversations.selectById(value.path("conversationId").asText());
                        if(conversation==null || !scope.canView(conversation.getCreatedBy(),conversation.getOwnerGroupId()))throw IqcException.accessDenied("原输入来源不再可见");}
                }catch(java.io.IOException error){throw IqcException.invalidState("生产输入来源无法解析");}
                fresh(env,actor);queued=executionService.getObject().queueRetrySystem(frame.taskId(),frame.executionId(),frame.attemptCount())!=null;fresh(env,actor);
                if(env.policy().effectiveUntil()!=null && !Instant.now().isBefore(env.policy().effectiveUntil()))throw IqcException.invalidState("重试有效期在排队期间结束");
            }
        }
        attempts.getObject().committed(handle,queued?1:0);return queued;
    }
    private void fresh(Envelope env,ScheduledActorAccessService.Resolved actor){Instant now=Instant.now();
        if(actor==null || !Objects.equals(actor.userId(),env.ownerBound().userId()) || !Objects.equals(actor.owner(),env.ownerBound().owner())
                || actor.checkedAt()==null || actor.checkedAt().isBefore(now.minusSeconds(30)) || actor.checkedAt().isAfter(now.plusSeconds(5))
                || actor.resourceCodes()==null || !actor.resourceCodes().containsAll(env.requiredGrants())
                || !Objects.equals(scope.owner(),actor.owner()) || scope.canViewAll()!=actor.all()
                || !Objects.equals(scope.groupId(),actor.groupId()) || (actor.all() && !env.ownerBound().all())
                || (actor.groupId()!=null && !Objects.equals(actor.groupId(),env.ownerBound().groupId())))throw IqcException.accessDenied("重试授权证据无效或过期");}
    private boolean matches(InspectionTask task,Frame frame){return failed(task) && Objects.equals(task.getCurrentExecutionId(),frame.executionId())
            && Objects.equals(task.getAttemptCount(),frame.attemptCount()) && Objects.equals(task.getRetryPolicyHash(),frame.policyHash());}
    @Transactional
    public void defer(Frame frame,Instant now){ScheduledActorAccessService.requireBackground();tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate()
            .eq(InspectionTask::getId,frame.taskId()).eq(InspectionTask::getCurrentExecutionId,frame.executionId()).eq(InspectionTask::getAttemptCount,frame.attemptCount())
            .eq(InspectionTask::getRetryPolicyHash,frame.policyHash()).in(InspectionTask::getStatus,List.of("FAILED","PARTIAL_FAILED"))
            .eq(InspectionTask::getAutomaticRetryEnabled,true).set(InspectionTask::getRetryNotBeforeUtc,PeriodicScheduleService.utc(now.plusSeconds(30)))
            .set(InspectionTask::getRetryError,"RETRY_DEPENDENCY_OR_AUTHORIZATION_UNAVAILABLE"));}
    @Transactional
    public void deferMalformed(String id,Instant now){ScheduledActorAccessService.requireBackground();tasks.update(null,Wrappers.<InspectionTask>lambdaUpdate()
            .eq(InspectionTask::getId,id).in(InspectionTask::getStatus,List.of("FAILED","PARTIAL_FAILED")).eq(InspectionTask::getAutomaticRetryEnabled,true)
            .set(InspectionTask::getRetryNotBeforeUtc,PeriodicScheduleService.utc(now.plusSeconds(30))).set(InspectionTask::getRetryError,"RETRY_EVIDENCE_UNAVAILABLE"));}
}
