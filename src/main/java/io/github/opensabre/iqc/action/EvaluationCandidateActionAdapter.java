package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;

/** A reviewed candidate is still subject to the existing gold annotation and independent approval. */
@Component @RequiredArgsConstructor
public class EvaluationCandidateActionAdapter implements ActionAdapter {
    private static final Definition DEFINITION=new Definition(ActionExecutionContract.Type.ADD_TO_EVALUATION_CANDIDATE,1,true);
    private final EvaluationCandidateActionService candidates;
    private final ResultReviewMapper reviews;
    private final ObjectMapper json;
    @Override public Definition definition(){return DEFINITION;}
    @Override public Set<String> requiredResources(){return Set.of("iqc:sample:manage");}
    @Override public void validateConfiguration(JsonNode configuration) {
        if(configuration==null || !configuration.isObject()
                || !configuration.path("sampleType").isTextual()
                || !Set.of("POSITIVE","NEGATIVE","FALSE_POSITIVE","FALSE_NEGATIVE").contains(configuration.path("sampleType").asText()))
            throw new IllegalArgumentException("Candidate action requires a sample type");
        var fields=configuration.fieldNames();
        while(fields.hasNext())if(!Set.of("sampleType","name","tags","reviewRevision").contains(fields.next()))
            throw new IllegalArgumentException("Unknown candidate configuration field");
        var name=configuration.get("name");
        if(name!=null && (!name.isTextual() || name.asText().isBlank() || name.asText().trim().length()>128))
            throw new IllegalArgumentException("Invalid candidate name");
        var revision=configuration.get("reviewRevision");
        if(revision!=null && (!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.intValue()<1))
            throw new IllegalArgumentException("Invalid candidate review revision");
        var tags=configuration.get("tags");
        if(tags!=null) {
            if(!tags.isArray() || tags.size()>50)throw new IllegalArgumentException("Invalid candidate tags");
            for(var tag:tags)if(!tag.isTextual() || tag.asText().isBlank() || tag.asText().length()>64)
                throw new IllegalArgumentException("Invalid candidate tag");
        }
    }
    @Override public boolean ready(ActionPolicy.Action action,ActionProductionSourceValidator.Validated source) {
        if(!Set.of("SUCCEEDED","PARTIAL_FAILED","FAILED").contains(Objects.toString(source.taskStatus(),"")))return false;
        int revision=action.configuration().path("reviewRevision").asInt(1);
        return reviews.selectCount(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getTargetType,"BUSINESS")
                .eq(ResultReview::getBusinessResultId,source.reference().resultId())
                .eq(ResultReview::getReviewRevision,revision).eq(ResultReview::getStatus,"COMPLETED"))>0;
    }
    @Override public Receipt execute(Request request) {
        Objects.requireNonNull(request);
        if(!DEFINITION.equals(request.adapter()))throw new IllegalArgumentException("Candidate adapter version mismatch");
        try {
            var policy=json.readValue(request.policySnapshotJson(),ActionPolicy.class);
            var action=policy.actions().stream().filter(value->request.source().actionId().equals(value.actionId())).findFirst().orElseThrow();
            if(action.type()!=DEFINITION.type() || action.effectiveAdapterVersion()!=DEFINITION.version())
                throw new IllegalArgumentException("Candidate policy mismatch");
            validateConfiguration(action.configuration());
            String id=candidates.create(request,action.configuration());
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,id,id,null);
        } catch(Exception unconfirmed) {
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,"CANDIDATE_CONFIRMATION_UNAVAILABLE");
        }
    }
}
