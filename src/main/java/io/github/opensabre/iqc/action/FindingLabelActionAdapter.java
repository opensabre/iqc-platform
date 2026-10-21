package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.label.FindingLabelActionConfiguration;
import io.github.opensabre.iqc.label.FindingLabelPolicy;
import io.github.opensabre.iqc.label.FindingLabelResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Writes through the original label module and returns only its committed artifact as a receipt. */
@Component @RequiredArgsConstructor
public class FindingLabelActionAdapter implements ActionAdapter {
    private final FindingLabelResultService labels;
    private final ObjectMapper json;
    @Override public Definition definition(){return FindingLabelResultService.DEFINITION;}
    @Override public void validateConfiguration(JsonNode configuration){FindingLabelActionConfiguration.parse(configuration);}
    @Override public boolean ready(ActionPolicy.Action action,ActionProductionSourceValidator.Validated source) {
        validateConfiguration(action.configuration());
        try {
            var input=json.readTree(source.inputSnapshotJson());
            if(input==null || !"iqc-action-production-input-v1".equals(input.path("schemaVersion").asText())
                    || !input.path("taskSnapshotJson").isTextual())return false;
            var snapshot=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,input.path("taskSnapshotJson").asText());
            var node=snapshot==null?null:snapshot.path("pipelineSnapshot").path("release").get("labelPolicy");
            if(node==null || node.isNull())return false;
            var policy=json.treeToValue(node,FindingLabelPolicy.class);
            policy.validateFrozen();
            FindingLabelActionConfiguration.requireBinding(policy,action.configuration());
            return true;
        } catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalidPrerequisite) {
            // Old published policies without a valid binding remain pending; no attempt or invented write.
            return false;
        }
    }
    @Override public Receipt reconcile(Request request) {
        String id=labels.lookup(request);
        return id==null ? null : new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,id,id,null);
    }
    @Override public Receipt execute(Request request) {
        Objects.requireNonNull(request);
        if(!definition().equals(request.adapter()))throw new IllegalArgumentException("UPDATE_LABEL adapter version mismatch");
        try {
            String id=labels.write(request); // Spring proxy commits its local transaction before this receipt is constructed.
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,id,id,null);
        } catch(Exception unconfirmed) {
            return new Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,"LABEL_CONFIRMATION_UNAVAILABLE");
        }
    }
}
