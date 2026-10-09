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
    public record View(String id,String actionId,String type,String status,Integer attemptCount,
                       LocalDateTime nextAttemptAtUtc,LocalDateTime claimedAtUtc,LocalDateTime settledAtUtc,
                       String outcome,String evidenceReference,String resultReference,String errorCode) { }
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final ActionExecutionMapper actions;
    private final IqcDataScope scope;

    @Transactional(readOnly=true)
    public List<View> history(String resultId) {
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
        return actions.selectList(Wrappers.<ActionExecution>lambdaQuery()
                .select(ActionExecution::getId,ActionExecution::getActionId,ActionExecution::getActionType,ActionExecution::getStatus,
                        ActionExecution::getAttemptCount,ActionExecution::getNextAttemptAtUtc,ActionExecution::getClaimedAtUtc,
                        ActionExecution::getSettledAtUtc,ActionExecution::getOutcome,ActionExecution::getEvidenceReference,
                        ActionExecution::getResultReference,ActionExecution::getLastErrorCode)
                .eq(ActionExecution::getSourceResultId,resultId).eq(ActionExecution::getTaskId,result.getTaskId())
                .eq(ActionExecution::getExecutionId,result.getExecutionId()).eq(ActionExecution::getConversationId,result.getConversationId())
                .eq(ActionExecution::getOwner,task.getCreatedBy()).orderByAsc(ActionExecution::getActionId).last("LIMIT 100"))
                .stream().map(row->new View(row.getId(),row.getActionId(),row.getActionType(),row.getStatus(),row.getAttemptCount(),
                        row.getNextAttemptAtUtc(),row.getClaimedAtUtc(),row.getSettledAtUtc(),row.getOutcome(),row.getEvidenceReference(),
                        row.getResultReference(),row.getLastErrorCode())).toList();
    }
}
