package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Owns local finding-label writes in the original result table. Caller is the transaction-free action runner. */
@Service @RequiredArgsConstructor
public class FindingLabelResultService {
    public static final ActionAdapter.Definition DEFINITION=new ActionAdapter.Definition(ActionExecutionContract.Type.UPDATE_LABEL,1,true);
    private final ActionProductionSourceValidator sources;
    private final ActionExecutionMapper actions;
    private final InspectionTaskMapper tasks;
    private final InspectionLabelResultMapper labels;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String write(ActionAdapter.Request request) {
        Objects.requireNonNull(request);
        if(!DEFINITION.equals(request.adapter()) || !Objects.equals(request.executionId(),request.idempotencyKey()))
            throw IqcException.invalidArgument("标签动作协议或领取标识无效");
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        var source=request.source();
        if(!Objects.equals(scope.owner(),source.owner()))throw IqcException.accessDenied("标签动作负责人不一致");
        var validated=sources.validate(new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),
                source.conversationId(),source.resultId(),source.owner()));
        scope.requireScheduledBound(validated.ownerBound());
        if(validated.policy()==null)throw IqcException.invalidState("标签动作缺少冻结动作政策");
        try {
            var action=validated.policy().actions().stream().filter(value->source.actionId().equals(value.actionId())).findFirst().orElseThrow();
            if(action.type()!=DEFINITION.type() || action.effectiveAdapterVersion()!=DEFINITION.version()
                    || ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)
                throw IqcException.invalidState("标签动作冻结类型或触发条件不一致");
            String policyBody=json.writeValueAsString(validated.policy());
            // Same lock order as claim: Task -> Execution -> Result -> Action, followed by the local artifact.
            var claimed=actions.selectOne(Wrappers.<ActionExecution>lambdaQuery().eq(ActionExecution::getId,request.executionId()).last("FOR UPDATE"));
            if(claimed==null)throw IqcException.invalidState("标签动作领取不存在");
            ActionEnqueueService.requireSame(claimed,source,action,validated,request.idempotencyKey(),policyBody);
            if(!"RUNNING".equals(claimed.getStatus()) || !Objects.equals(claimed.getAttemptCount(),request.attempt())
                    || claimed.getVersion()==null || claimed.getVersion()<1 || claimed.getClaimToken()==null
                    || !claimed.getClaimToken().matches("[a-f0-9]{32}") || claimed.getClaimedAtUtc()==null
                    || !Objects.equals(request.inputSnapshotHash(),claimed.getInputSnapshotHash())
                    || !Objects.equals(request.inputSnapshotJson(),claimed.getInputSnapshotJson())
                    || !Objects.equals(request.policySnapshotJson(),policyBody))
                throw IqcException.invalidState("标签动作领取或冻结请求已失效");
            var task=tasks.selectById(source.taskId()); // Source validator already holds the Task lock.
            FindingLabelTaskDirectory.expected(task,json);
            var root=json.readTree(task.getRuleSnapshotJson());
            var release=json.treeToValue(root.path("pipelineSnapshot").path("release"),PipelineRelease.class);
            var policy=release.labelPolicy();
            if(policy==null)throw IqcException.invalidState("标签动作缺少冻结标签政策");
            var binding=FindingLabelActionConfiguration.requireBinding(policy,action.configuration());
            var projection=FindingLabelProjection.project(policy.projectionBinding(binding),validated.context().findings().get(binding.findingCode()));
            String body=json.writeValueAsString(projection);
            if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4*1024*1024)
                throw IqcException.invalidState("标签投影超过4MiB，不能截断保存");
            String id="fl-"+SelectorAssetService.hash(json.writeValueAsString(List.of(source.resultId(),binding.labelId(),binding.valueCode()))).substring(0,60);
            var existing=find(source.resultId(),binding.labelId(),binding.valueCode());
            checkBeforeWrite(action,request.attempt());
            if(existing!=null){requireArtifact(existing,id,body,request,validated);checkBeforeWrite(action,request.attempt());return existing.getId();}
            var row=new InspectionLabelResult();row.setId(id);row.setConversationResultId(source.resultId());
            row.setLabelId(binding.labelId());row.setLabelVersionNo(binding.labelVersion());row.setValueCode(binding.valueCode());
            row.setValueJson(body);row.setGenerationSource(LabelResultSourceValidator.FINDING);
            row.setSourceFindingCode(binding.findingCode());row.setSourceActionExecutionId(request.executionId());
            row.setSourceInputHash(validated.inputSnapshotHash());row.setSourceProjectionHash(SelectorAssetService.hash(body));
            row.setCreatedBy(source.owner()); // Parent task owns the existing label data scope.
            LabelResultSourceValidator.validate(row,json);
            try {
                if(labels.insert(row)!=1)throw IqcException.invalidState("标签投影保存失败");
            } catch(org.springframework.dao.DuplicateKeyException concurrent) {
                var winner=find(source.resultId(),binding.labelId(),binding.valueCode());
                requireArtifact(winner,id,body,request,validated);
            }
            checkBeforeWrite(action,request.attempt());
            return id;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | java.util.NoSuchElementException failure) {
            throw IqcException.invalidState("标签动作冻结政策或绑定损坏");
        }
    }
    private InspectionLabelResult find(String resultId,String labelId,String valueCode) {
        return labels.selectOne(Wrappers.<InspectionLabelResult>lambdaQuery().eq(InspectionLabelResult::getConversationResultId,resultId)
                .eq(InspectionLabelResult::getLabelId,labelId).eq(InspectionLabelResult::getValueCode,valueCode));
    }
    private void requireArtifact(InspectionLabelResult row,String id,String body,ActionAdapter.Request request,ActionProductionSourceValidator.Validated validated) {
        if(row==null)throw IqcException.invalidState("标签幂等产物不存在");
        LabelResultSourceValidator.validate(row,json);
        if(!Objects.equals(id,row.getId()) || !Objects.equals(body,row.getValueJson())
                || !Objects.equals(row.getConversationResultId(),request.source().resultId())
                || !Objects.equals(row.getCreatedBy(),request.source().owner())
                || !Objects.equals(row.getSourceInputHash(),validated.inputSnapshotHash()))
            throw IqcException.invalidState("同一标签值的已保存产物与冻结来源不一致");
        var origin=actions.selectById(row.getSourceActionExecutionId());
        if(origin==null || origin.getAttemptCount()==null || origin.getAttemptCount()<1 || origin.getClaimToken()==null
                || !origin.getClaimToken().matches("[a-f0-9]{32}") || origin.getClaimedAtUtc()==null)
            throw IqcException.invalidState("标签原始动作缺少实际领取来源");
        var declared=validated.policy().actions().stream().filter(value->Objects.equals(origin.getActionId(),value.actionId())).findFirst().orElseThrow();
        var oldSource=new ActionExecutionContract.Source(request.source().taskId(),request.source().executionId(),request.source().conversationId(),
                request.source().resultId(),validated.decisionReference(),request.source().actionPolicyHash(),declared.actionId(),request.source().owner());
        if(declared.type()!=DEFINITION.type() || declared.effectiveAdapterVersion()!=DEFINITION.version()
                || !FindingLabelActionConfiguration.parse(declared.configuration()).equals(FindingLabelActionConfiguration.parse(
                    validated.policy().actions().stream().filter(value->request.source().actionId().equals(value.actionId())).findFirst().orElseThrow().configuration())))
            throw IqcException.invalidState("标签原始动作绑定不一致");
        ActionEnqueueService.requireSame(origin,oldSource,declared,validated,oldSource.idempotencyKey(declared.type()),request.policySnapshotJson());
    }
    private void checkBeforeWrite(ActionPolicy.Action action,int attempt) {
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        if(attempt>(action.retry()==null?1:action.retry().maximumAttempts())
                || action.retry()!=null && action.retry().effectiveUntil()!=null && !Instant.now().isBefore(action.retry().effectiveUntil()))
            throw IqcException.invalidState("标签动作次数或冻结有效期已耗尽");
    }
}
