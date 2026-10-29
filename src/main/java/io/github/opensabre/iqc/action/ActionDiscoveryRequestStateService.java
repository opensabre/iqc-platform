package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Service @RequiredArgsConstructor
public class ActionDiscoveryRequestStateService {
    public record Response(String sourceResultId,String status) { }
    private final ConversationInspectionResultMapper results;
    private final ActionProductionSourceValidator sources;
    private final ActionDiscoveryMapper discovery;
    private final IqcDataScope scope;
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Response request(String resultId,ScheduledActorAccessService.CreationBound current) {
        if(current==null || !Objects.equals(current.owner(),scope.owner()) || "system".equals(scope.owner()))
            throw IqcException.accessDenied("请求动作检查需要当前负责人身份");
        var result=results.selectOne(Wrappers.<ConversationInspectionResult>lambdaQuery()
                .select(ConversationInspectionResult::getId,ConversationInspectionResult::getTaskId,
                        ConversationInspectionResult::getExecutionId,ConversationInspectionResult::getConversationId)
                .eq(ConversationInspectionResult::getId,resultId));
        if(result==null)throw IqcException.notFound("动作来源结果不存在");
        // Validator locks Task -> Execution -> Result and requires creator ownership, not broad read access.
        var validated=sources.validate(new ActionProductionSourceValidator.Reference(result.getTaskId(),result.getExecutionId(),
                result.getConversationId(),result.getId(),current.owner()));
        if(validated.policy()==null || validated.policy().actions().isEmpty())
            throw IqcException.invalidState("生产来源没有冻结动作策略");
        var bound=validated.ownerBound();
        if(bound==null || !Objects.equals(bound.userId(),current.userId()) || !Objects.equals(bound.owner(),current.owner()))
            throw IqcException.accessDenied("动作负责人稳定身份已变化");
        // Completed sources never reset: this endpoint does not retry or resend existing executions.
        if(discovery.isComplete(resultId))return new Response(resultId,"ALREADY_DISCOVERED");
        discovery.defer(resultId,LocalDateTime.now(ZoneOffset.UTC));
        return new Response(resultId,"DISCOVERY_REQUESTED");
    }
}
