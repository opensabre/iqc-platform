package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.risk.RiskEventActionService;
import io.github.opensabre.iqc.risk.RiskEventConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;

/** Actual committed risk artifacts; unknown receipts never authorize another execution. */
@Component @RequiredArgsConstructor
public class RiskEventActionAdapter implements ActionAdapter {
    private final RiskEventActionService events;
    private final ObjectMapper json;
    @Override public Definition definition(){return RiskEventActionService.DEFINITION;}
    @Override public Set<String> requiredResources(){return Set.of(RiskEventActionService.CREATE_RESOURCE);}
    @Override public void validateConfiguration(JsonNode configuration){RiskEventConfiguration.parse(configuration);}
    @Override public boolean ready(ActionPolicy.Action action,ActionProductionSourceValidator.Validated source) {
        validateConfiguration(action.configuration());
        String risk=source.context().riskLevel();
        return risk!=null && Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(risk);
    }
    @Override public Receipt execute(Request request){return confirm(request,false);}
    @Override public Receipt reconcile(Request request){return confirm(request,true);}
    private Receipt confirm(Request request,boolean readOnly) {
        Objects.requireNonNull(request);
        if(!definition().equals(request.adapter()))throw new IllegalArgumentException("CREATE_RISK_EVENT adapter version mismatch");
        try {
            var policy=json.readValue(request.policySnapshotJson(),ActionPolicy.class);
            var action=policy.actions().stream().filter(value->request.source().actionId().equals(value.actionId())).findFirst().orElseThrow();
            if(action.type()!=definition().type() || action.effectiveAdapterVersion()!=definition().version())
                throw new IllegalArgumentException("CREATE_RISK_EVENT policy mismatch");
            validateConfiguration(action.configuration());
            String id=readOnly?events.lookup(request):events.create(request);
            if(id==null && readOnly)return null;
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,id,id,null);
        } catch(Exception unconfirmed) {
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,"RISK_CONFIRMATION_UNAVAILABLE");
        }
    }
}
