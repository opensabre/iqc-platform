package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;

/** Creates an actual business review through its owning service, never an alternate review store. */
@Component @RequiredArgsConstructor
public class BusinessReviewActionAdapter implements ActionAdapter {
    private static final Definition DEFINITION=new Definition(ActionExecutionContract.Type.CREATE_REVIEW,1,true);
    private final BusinessReviewActionService reviews;
    private final ObjectMapper json;
    @Override public Definition definition(){return DEFINITION;}
    @Override public Set<String> requiredResources(){return Set.of("iqc:review:create");}
    @Override public void validateConfiguration(JsonNode configuration) {
        if(configuration==null || !configuration.isObject() || !configuration.path("reason").isTextual()
                || configuration.path("reason").asText().isBlank() || configuration.path("reason").asText().trim().length()>1000)
            throw new IllegalArgumentException("CREATE_REVIEW requires a bounded reason");
        var fields=configuration.fieldNames();
        while(fields.hasNext())if(!Set.of("reason","expectedRevision").contains(fields.next()))
            throw new IllegalArgumentException("Unknown CREATE_REVIEW configuration field");
        var revision=configuration.get("expectedRevision");
        if(revision!=null && (!revision.isIntegralNumber() || !revision.canConvertToInt()
                || revision.intValue()<0 || revision.intValue()==Integer.MAX_VALUE))
            throw new IllegalArgumentException("Invalid CREATE_REVIEW expected revision");
    }
    @Override public Receipt execute(Request request) { return confirm(request,false); }
    /** A recovery lookup never calls the owning create operation. */
    @Override public Receipt reconcile(Request request) { return confirm(request,true); }
    private Receipt confirm(Request request,boolean readOnly) {
        Objects.requireNonNull(request);
        if(!DEFINITION.equals(request.adapter()))throw new IllegalArgumentException("CREATE_REVIEW adapter version mismatch");
        try {
            var policy=json.readValue(request.policySnapshotJson(),ActionPolicy.class);
            var action=policy.actions().stream().filter(value->request.source().actionId().equals(value.actionId())).findFirst().orElseThrow();
            if(action.type()!=DEFINITION.type() || action.effectiveAdapterVersion()!=DEFINITION.version())
                throw new IllegalArgumentException("CREATE_REVIEW policy mismatch");
            validateConfiguration(action.configuration());
            String id=readOnly ? reviews.lookup(request,action.configuration()) : reviews.open(request,action.configuration());
            if(readOnly && id==null)return null;
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,id,id,null);
        } catch(Exception unconfirmed) {
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,"REVIEW_CONFIRMATION_UNAVAILABLE");
        }
    }
}
