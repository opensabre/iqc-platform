package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.Set;

/** Local owning-operation transaction; no external I/O and no Action-row lock. */
@Service @RequiredArgsConstructor
public class BusinessReviewActionService {
    private final ActionProductionSourceValidator sources;
    private final BusinessItemReviewService reviews;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    /** Recovery reads committed owning artifacts, with no send grants, source refresh or writes. */
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String lookup(ActionAdapter.Request request,JsonNode configuration) {
        if(!Objects.equals(scope.owner(),request.source().owner()))
            throw IqcException.accessDenied("动作核对负责人不一致");
        var artifact=reviews.lookupRequest(request.source().resultId(), configuration.path("expectedRevision").asInt(0), request.idempotencyKey(), configuration.path("reason").asText());
        return artifact==null ? null : artifact.getId();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String open(ActionAdapter.Request request,JsonNode configuration) {
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        var source=request.source();
        if(!Objects.equals(scope.owner(),source.owner()))throw IqcException.accessDenied("复核动作负责人不一致");
        var validated=sources.validate(new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),
                source.conversationId(),source.resultId(),source.owner()));
        if(!Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED").contains(validated.taskStatus())
                || !Objects.equals(request.inputSnapshotHash(),validated.inputSnapshotHash())
                || !Objects.equals(request.inputSnapshotJson(),validated.inputSnapshotJson()))
            throw IqcException.invalidState("复核动作生产来源已变化或任务尚未结束");
        try {
            if(!Objects.equals(request.policySnapshotJson(),json.writeValueAsString(validated.policy())))
                throw IqcException.invalidState("复核动作冻结策略已变化");
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw IqcException.invalidState("复核策略损坏");}
        int revision=configuration.path("expectedRevision").asInt(0);
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        var review=reviews.request(source.resultId(),revision,request.idempotencyKey(),configuration.path("reason").asText());
        if(review==null || review.getId()==null || !"BUSINESS".equals(review.getTargetType())
                || !Objects.equals(review.getBusinessResultId(),source.resultId())
                || !Objects.equals(review.getCreatedBy(),source.owner()) || !Objects.equals(review.getReviewRevision(),revision+1))
            throw IqcException.invalidState("复核动作产物归属不一致");
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        return review.getId();
    }
}
