package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.BusinessReviewSampleService;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.Set;

/** Reuses reviewed business candidates, no duplicate sample table or automatic gold approval. */
@Service @RequiredArgsConstructor
public class EvaluationCandidateActionService {
    private final ActionProductionSourceValidator sources;
    private final BusinessReviewSampleService samples;
    private final ResultReviewMapper reviews;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String create(ActionAdapter.Request request,JsonNode configuration) {
        scope.requireScheduledResources(Set.of("iqc:sample:manage"));
        var source=request.source();
        if(!Objects.equals(scope.owner(),source.owner()))throw IqcException.accessDenied("候选动作负责人不一致");
        var validated=sources.validate(new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),
                source.conversationId(),source.resultId(),source.owner()));
        if(!Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED").contains(validated.taskStatus())
                || !Objects.equals(request.inputSnapshotHash(),validated.inputSnapshotHash())
                || !Objects.equals(request.inputSnapshotJson(),validated.inputSnapshotJson()))
            throw IqcException.invalidState("候选动作生产来源已变化或任务尚未结束");
        try {
            if(!Objects.equals(request.policySnapshotJson(),json.writeValueAsString(validated.policy())))
                throw IqcException.invalidState("候选动作冻结策略已变化");
            int revision=configuration.path("reviewRevision").asInt(1);
            var review=reviews.selectOne(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getTargetType,"BUSINESS")
                    .eq(ResultReview::getBusinessResultId,source.resultId()).eq(ResultReview::getReviewRevision,revision)
                    .eq(ResultReview::getStatus,"COMPLETED"));
            if(review==null)throw IqcException.invalidState("指定轮次的业务复核尚未完成");
            scope.requireScheduledResources(Set.of("iqc:sample:manage"));
            var tags=configuration.has("tags")?configuration.get("tags"):json.createArrayNode();
            var sample=samples.create(source.resultId(),review.getId(),request.idempotencyKey(),
                    configuration.has("name")?configuration.get("name").asText():null,
                    configuration.path("sampleType").asText(),tags.toString());
            if(sample==null || sample.getId()==null || !Objects.equals(sample.getSourceResultId(),source.resultId())
                    || !Objects.equals(sample.getConversationId(),source.conversationId())
                    || !Objects.equals(sample.getCreatedBy(),source.owner()))
                throw IqcException.invalidState("候选动作产物归属不一致");
            var expected=json.readTree(sample.getExpectedJson());
            if(expected==null || !"iqc-reviewed-business-candidate-v1".equals(expected.path("schemaVersion").asText())
                    || !Objects.equals(review.getId(),expected.path("reviewId").asText())
                    || expected.path("reviewRevision").asInt(-1)!=revision)
                throw IqcException.invalidState("候选动作复核来源不一致");
            scope.requireScheduledResources(Set.of("iqc:sample:manage"));
            return sample.getId();
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw IqcException.invalidState("候选冻结快照损坏");}
    }
}
