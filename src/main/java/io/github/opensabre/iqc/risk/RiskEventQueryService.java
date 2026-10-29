package io.github.opensabre.iqc.risk;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class RiskEventQueryService {
    public record View(String id,String sourceResultId,String sourceActionExecutionId,String category,String title,
                       String description,String riskLevel,String status,String version) { }
    private final RiskEventMapper events;
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final ActionExecutionMapper actions;
    private final ActionProductionSourceValidator sources;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<View> list(String resultId) {
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}"))
            throw IqcException.invalidArgument("风险来源结果标识无效");
        var result=results.selectById(resultId);
        if(result==null)throw IqcException.notFound("风险来源结果不存在");
        var task=tasks.selectById(result.getTaskId());
        if(task==null || !scope.canView(task.getCreatedBy(),task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权查看风险来源结果");
        var rows=events.selectList(Wrappers.<RiskEvent>lambdaQuery().eq(RiskEvent::getSourceResultId,resultId)
                .eq(RiskEvent::getCreatedBy,task.getCreatedBy()).orderByAsc(RiskEvent::getId).last("LIMIT 100"));
        if(rows.isEmpty())return List.of();
        var validated=sources.validateHistorical(new ActionProductionSourceValidator.Reference(task.getId(),result.getExecutionId(),
                result.getConversationId(),resultId,task.getCreatedBy()));
        if(validated.policy()==null)throw IqcException.invalidState("风险事件缺少实际生产政策");
        try {
            String policyBody=json.writeValueAsString(validated.policy());
            var views=new java.util.ArrayList<View>();
            for(var row:rows) {
                var origin=actions.selectById(row.getSourceActionExecutionId());
                if(origin==null || origin.getAttemptCount()==null || origin.getAttemptCount()<1 || origin.getClaimedAtUtc()==null
                        || origin.getClaimToken()==null || !origin.getClaimToken().matches("[a-f0-9]{32}"))
                    throw IqcException.invalidState("风险事件缺少实际动作领取");
                var action=validated.policy().actions().stream().filter(a->a.actionId().equals(origin.getActionId())).findFirst().orElseThrow();
                if(action.type()!=ActionExecutionContract.Type.CREATE_RISK_EVENT || action.effectiveAdapterVersion()!=1
                        || ActionPolicy.evaluate(action.condition(),validated.context())!=ActionPolicy.Match.MATCH)
                    throw IqcException.invalidState("风险事件原始动作不一致");
                var source=new ActionExecutionContract.Source(task.getId(),result.getExecutionId(),result.getConversationId(),resultId,
                        validated.decisionReference(),SelectorAssetService.hash(policyBody),action.actionId(),task.getCreatedBy());
                String key=source.idempotencyKey(action.type());
                ActionEnqueueService.requireSame(origin,source,action,validated,key,policyBody);
                var metadata=RiskEventConfiguration.parse(action.configuration());
                String risk=validated.context().riskLevel();
                if(!java.util.Objects.equals(row.getId(),"risk-"+key.substring(0,59))
                        || !java.util.Objects.equals(row.getSourceInputHash(),validated.inputSnapshotHash())
                        || !java.util.Objects.equals(row.getCategory(),metadata.category()) || !java.util.Objects.equals(row.getTitle(),metadata.title())
                        || !java.util.Objects.equals(row.getDescription(),metadata.description()) || !java.util.Objects.equals(row.getRiskLevel(),risk)
                        || !java.util.Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(risk==null?"":risk)
                        || row.getVersion()==null || row.getVersion()<0
                        || !java.util.Set.of("OPEN","IN_PROGRESS","RESOLVED","DISMISSED").contains(row.getStatus()==null?"":row.getStatus()))
                    throw IqcException.invalidState("风险事件与冻结生产来源不一致");
                views.add(new View(row.getId(),resultId,row.getSourceActionExecutionId(),row.getCategory(),row.getTitle(),
                        row.getDescription(),risk,row.getStatus(),row.getVersion().toString()));
            }
            return List.copyOf(views);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | java.util.NoSuchElementException invalid) {
            throw IqcException.invalidState("风险事件冻结生产政策损坏");
        }
    }
}
