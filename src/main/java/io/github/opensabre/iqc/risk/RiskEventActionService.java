package io.github.opensabre.iqc.risk;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Owning transaction for actual risk artifacts, separate from completed analysis and ledger settlement. */
@Service @RequiredArgsConstructor
public class RiskEventActionService {
    public static final ActionAdapter.Definition DEFINITION=new ActionAdapter.Definition(ActionExecutionContract.Type.CREATE_RISK_EVENT,1,true);
    public static final String CREATE_RESOURCE="iqc:risk:create";
    private final ActionProductionSourceValidator sources;
    private final ActionExecutionMapper actions;
    private final RiskEventMapper events;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private record Prepared(ActionPolicy.Action action,RiskEventConfiguration.Metadata metadata,String risk) { }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String create(ActionAdapter.Request request) {
        var prepared=prepare(request,false);
        check(prepared.action(),request.attempt());
        var existing=find(request.executionId());
        if(existing!=null){requireArtifact(existing,request,prepared);check(prepared.action(),request.attempt());return existing.getId();}
        var row=new RiskEvent();row.setId(id(request));row.setSourceActionExecutionId(request.executionId());
        row.setSourceResultId(request.source().resultId());row.setSourceInputHash(request.inputSnapshotHash());
        row.setCategory(prepared.metadata().category());row.setTitle(prepared.metadata().title());
        row.setDescription(prepared.metadata().description());row.setRiskLevel(prepared.risk());
        row.setStatus("OPEN");row.setVersion(0L);row.setCreatedBy(request.source().owner());
        try {if(events.insert(row)!=1)throw IqcException.invalidState("风险事件保存失败");}
        catch(org.springframework.dao.DuplicateKeyException concurrent){requireArtifact(find(request.executionId()),request,prepared);}
        check(prepared.action(),request.attempt());
        return row.getId();
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public String lookup(ActionAdapter.Request request) {
        var prepared=prepare(request,true);
        var row=find(request.executionId());
        if(row==null)return null;
        requireArtifact(row,request,prepared);
        return row.getId();
    }
    private Prepared prepare(ActionAdapter.Request request,boolean historical) {
        Objects.requireNonNull(request);
        if(!DEFINITION.equals(request.adapter()) || !Objects.equals(scope.owner(),request.source().owner()))
            throw IqcException.accessDenied("风险动作协议或负责人不一致");
        if(!historical){scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);scope.requireScheduledResources(Set.of(CREATE_RESOURCE));}
        var s=request.source();
        var ref=new ActionProductionSourceValidator.Reference(s.taskId(),s.executionId(),s.conversationId(),s.resultId(),s.owner());
        var validated=historical?sources.validateHistorical(ref):sources.validate(ref);
        if(!historical)scope.requireScheduledBound(validated.ownerBound());
        if(validated.policy()==null)throw IqcException.invalidState("风险动作缺少冻结政策");
        try {
            var action=validated.policy().actions().stream().filter(a->s.actionId().equals(a.actionId())).findFirst().orElseThrow();
            String body=json.writeValueAsString(validated.policy());
            var row=historical?actions.selectById(request.executionId()):actions.selectOne(Wrappers.<ActionExecution>lambdaQuery()
                    .eq(ActionExecution::getId,request.executionId()).last("FOR UPDATE"));
            if(row==null || action.type()!=DEFINITION.type() || action.effectiveAdapterVersion()!=1
                    || ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH
                    || !Objects.equals(row.getAttemptCount(),request.attempt()) || row.getClaimedAtUtc()==null
                    || row.getClaimToken()==null || !row.getClaimToken().matches("[a-f0-9]{32}")
                    || !historical && (!"RUNNING".equals(row.getStatus()) || row.getVersion()==null || row.getVersion()<1)
                    || !Objects.equals(request.policySnapshotJson(),body)
                    || !Objects.equals(request.inputSnapshotJson(),validated.inputSnapshotJson())
                    || !Objects.equals(request.inputSnapshotHash(),validated.inputSnapshotHash()))
                throw IqcException.invalidState("风险动作领取或冻结来源不一致");
            ActionEnqueueService.requireSame(row,s,action,validated,request.idempotencyKey(),body);
            String risk=validated.context().riskLevel();
            if(!Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(risk==null?"":risk))
                throw IqcException.invalidState("风险事件需要实际已确认的非空风险等级");
            return new Prepared(action,RiskEventConfiguration.parse(action.configuration()),risk);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | java.util.NoSuchElementException invalid) {
            throw IqcException.invalidState("风险动作冻结政策损坏");
        }
    }
    private RiskEvent find(String actionId){return events.selectOne(Wrappers.<RiskEvent>lambdaQuery().eq(RiskEvent::getSourceActionExecutionId,actionId));}
    private String id(ActionAdapter.Request request){return "risk-"+request.idempotencyKey().substring(0,59);}
    private void requireArtifact(RiskEvent row,ActionAdapter.Request request,Prepared prepared) {
        if(row==null || !Objects.equals(row.getId(),id(request)) || !Objects.equals(row.getSourceActionExecutionId(),request.executionId())
                || !Objects.equals(row.getSourceResultId(),request.source().resultId()) || !Objects.equals(row.getSourceInputHash(),request.inputSnapshotHash())
                || !Objects.equals(row.getCreatedBy(),request.source().owner()) || !Objects.equals(row.getRiskLevel(),prepared.risk())
                || !Objects.equals(row.getCategory(),prepared.metadata().category()) || !Objects.equals(row.getTitle(),prepared.metadata().title())
                || !Objects.equals(row.getDescription(),prepared.metadata().description()) || row.getVersion()==null || row.getVersion()<0
                || !Set.of("OPEN","IN_PROGRESS","RESOLVED","DISMISSED").contains(row.getStatus()==null?"":row.getStatus()))
            throw IqcException.invalidState("风险事件产物与实际冻结来源不一致");
    }
    private void check(ActionPolicy.Action action,int attempt) {
        scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);scope.requireScheduledResources(Set.of(CREATE_RESOURCE));
        if(attempt>(action.retry()==null?1:action.retry().maximumAttempts())
                || action.retry()!=null && action.retry().effectiveUntil()!=null && !Instant.now().isBefore(action.retry().effectiveUntil()))
            throw IqcException.invalidState("风险动作有效期或尝试预算耗尽");
    }
}
