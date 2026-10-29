package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.quality.evaluation.MinedCandidateSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;

/** Version 2 mines unlabelled candidates; version 1 remains reviewed-business feedback. */
@Component @RequiredArgsConstructor
public class MinedEvaluationCandidateActionAdapter implements ActionAdapter {
    private static final Definition DEFINITION=new Definition(ActionExecutionContract.Type.ADD_TO_EVALUATION_CANDIDATE,MinedEvaluationCandidateService.ADAPTER_VERSION,true);
    private final MinedEvaluationCandidateService candidates;
    private final ObjectMapper json;
    @Override public Definition definition(){return DEFINITION;}
    @Override public Set<String> requiredResources(){return Set.of("iqc:sample:manage");}
    @Override public void validateConfiguration(JsonNode configuration){MinedCandidateSelection.validateConfiguration(configuration);}
    @Override public boolean ready(ActionPolicy.Action action,ActionProductionSourceValidator.Validated source){
        return Set.of("RUNNING","SUCCEEDED","PARTIAL_FAILED","FAILED").contains(Objects.toString(source.taskStatus(),""))
                &&!MinedCandidateSelection.reasons(source.context(),action.configuration()).isEmpty();
    }
    @Override public Receipt execute(Request request){return confirm(request,false);}
    @Override public Receipt reconcile(Request request){return confirm(request,true);}
    private Receipt confirm(Request request,boolean readOnly){
        Objects.requireNonNull(request);
        if(!DEFINITION.equals(request.adapter()))throw new IllegalArgumentException("Candidate mining adapter version mismatch");
        try{
            var policy=ActionFrozenPolicy.readFrozen(json,request.policySnapshotJson(),request.inputSnapshotJson());
            var action=policy.actions().stream().filter(value->request.source().actionId().equals(value.actionId())).findFirst().orElseThrow();
            if(action.type()!=DEFINITION.type()||action.effectiveAdapterVersion()!=DEFINITION.version())throw new IllegalArgumentException("Candidate mining policy mismatch");
            validateConfiguration(action.configuration());
            String id=readOnly?candidates.lookup(request,action.configuration()):candidates.create(request,action.configuration());
            if(readOnly&&id==null)return null;
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,id,id,null);
        }catch(Exception unconfirmed){return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,"MINED_CANDIDATE_CONFIRMATION_UNAVAILABLE");}
    }
}
