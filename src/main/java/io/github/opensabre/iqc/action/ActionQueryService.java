package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** Status projection only: frozen messages, policy configuration and identity bounds are never returned. */
@Service
@RequiredArgsConstructor
public class ActionQueryService {
    public record View(String id,Long version,String actionId,String type,String status,Integer attemptCount,
                       LocalDateTime nextAttemptAtUtc,LocalDateTime claimedAtUtc,LocalDateTime settledAtUtc,
                       String outcome,String evidenceReference,String resultReference,String errorCode,EventPage history) { }
    /** Public state excludes claim tokens and all frozen source content. */
    public record State(String status,Integer attemptCount,String claimedAtUtc,String settledAtUtc,String nextAttemptAtUtc,
                        String outcome,String evidenceReference,String resultReference,String errorCode) { }
    public record Event(String version,String previousVersion,State previousState,State state,String actor,LocalDateTime recordedAtUtc,String reconciliationReason) { }
    public record EventPage(List<Event> events,String nextBeforeVersion,boolean earlierHistoryUnavailable) { }
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final ActionExecutionMapper actions;
    private final IqcDataScope scope;
    private final ActionStateEventMapper events;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    @Transactional(readOnly=true)
    public List<View> history(String resultId) {return history(resultId,null,false,null);}

    /** Optional single-action journal page reuses exactly the original result/history authorization. */
    @Transactional(readOnly=true)
    public List<View> history(String resultId,String actionId,boolean includeHistory,String beforeVersion) {
        if(actionId!=null && !actionId.matches("[a-f0-9]{64}"))
            throw IqcException.invalidArgument("动作记录标识无效");
        if(includeHistory && actionId==null || beforeVersion!=null && !includeHistory)
            throw IqcException.invalidArgument("历史分页必须指定动作并启用历史读取");
        Long before=null;
        if(beforeVersion!=null) {
            try {
                if(!beforeVersion.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();
                before=Long.valueOf(beforeVersion);
            }catch(NumberFormatException error){throw IqcException.invalidArgument("历史版本游标无效");}
        }
        if(resultId==null || !resultId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,63}"))
            throw IqcException.invalidArgument("动作来源结果标识无效");
        var result=results.selectOne(Wrappers.<ConversationInspectionResult>lambdaQuery()
                .select(ConversationInspectionResult::getId,ConversationInspectionResult::getTaskId,
                        ConversationInspectionResult::getExecutionId,ConversationInspectionResult::getConversationId)
                .eq(ConversationInspectionResult::getId,resultId));
        if(result==null)throw IqcException.notFound("动作来源结果不存在");
        var task=tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery()
                .select(InspectionTask::getId,InspectionTask::getCreatedBy,InspectionTask::getOwnerGroupId)
                .eq(InspectionTask::getId,result.getTaskId()));
        if(task==null || !scope.canView(task.getCreatedBy(),task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权查看动作来源结果");
        var rows=actions.selectList(Wrappers.<ActionExecution>lambdaQuery()
                .select(ActionExecution::getId,ActionExecution::getVersion,ActionExecution::getActionId,ActionExecution::getActionType,ActionExecution::getStatus,
                        ActionExecution::getAttemptCount,ActionExecution::getNextAttemptAtUtc,ActionExecution::getClaimedAtUtc,
                        ActionExecution::getSettledAtUtc,ActionExecution::getOutcome,ActionExecution::getEvidenceReference,
                        ActionExecution::getResultReference,ActionExecution::getLastErrorCode)
                .eq(ActionExecution::getSourceResultId,resultId).eq(ActionExecution::getTaskId,result.getTaskId())
                .eq(ActionExecution::getExecutionId,result.getExecutionId()).eq(ActionExecution::getConversationId,result.getConversationId())
                .eq(ActionExecution::getOwner,task.getCreatedBy()).eq(actionId!=null,ActionExecution::getId,actionId)
                .orderByAsc(ActionExecution::getActionId).last("LIMIT 100"));
        if(actionId!=null && rows.isEmpty())throw IqcException.notFound("动作记录不存在或不属于该结果");
        EventPage page=includeHistory?eventPage(actionId,resultId,before):null;
        return rows.stream().map(row->new View(row.getId(),row.getVersion(),row.getActionId(),row.getActionType(),row.getStatus(),row.getAttemptCount(),
                        row.getNextAttemptAtUtc(),row.getClaimedAtUtc(),row.getSettledAtUtc(),row.getOutcome(),row.getEvidenceReference(),
                        row.getResultReference(),row.getLastErrorCode(),page)).toList();
    }
    private EventPage eventPage(String actionId,String resultId,Long before) {
        var rows=events.page(actionId,resultId,before);
        boolean more=rows.size()>50;
        var visible=rows.stream().limit(50).map(row->new Event(row.getVersion().toString(),
                row.getPreviousVersion()==null?null:row.getPreviousVersion().toString(),
                state(row.getPreviousStateJson()),state(row.getStateJson()),row.getActor(),row.getRecordedAtUtc(),row.getReconciliationReason())).toList();
        String next=more?visible.getLast().version():null;
        boolean gap=!more && (visible.isEmpty() || visible.getLast().previousVersion()!=null);
        return new EventPage(visible,next,gap);
    }
    private State state(String projection) {
        if(projection==null)return null;
        try {return json.readValue(projection,State.class);}
        catch(com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("动作历史状态投影无效",error);
        }
    }

}
