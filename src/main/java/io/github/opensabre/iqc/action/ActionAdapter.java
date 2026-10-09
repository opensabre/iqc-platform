package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.util.Objects;

/** Domain adapter boundary. Implementations reuse the owning service, never mark simulation as success. */
public interface ActionAdapter {
    record Definition(ActionExecutionContract.Type type,int version,boolean retrySafe) {
        public Definition {
            Objects.requireNonNull(type);
            if(version<1)throw new IllegalArgumentException("Action adapter version must be positive");
        }
    }
    record Request(String executionId,String idempotencyKey,ActionExecutionContract.Source source,
                   Definition adapter,int attempt,String inputSnapshotJson,String inputSnapshotHash,
                   String policySnapshotJson) {
        public Request {
            reference(executionId,64);Objects.requireNonNull(source);Objects.requireNonNull(adapter);
            if(attempt<1 || !source.idempotencyKey(adapter.type()).equals(idempotencyKey))
                throw new IllegalArgumentException("Action adapter request identity mismatch");
            if(inputSnapshotJson==null || inputSnapshotJson.isBlank() || inputSnapshotHash==null
                    || !SelectorAssetService.hash(inputSnapshotJson).equals(inputSnapshotHash))
                throw new IllegalArgumentException("Action adapter input snapshot mismatch");
            if(policySnapshotJson==null || policySnapshotJson.isBlank()
                    || !SelectorAssetService.hash(policySnapshotJson).equals(source.actionPolicyHash()))
                throw new IllegalArgumentException("Action adapter policy snapshot mismatch");
        }
    }
    /** Evidence is a reference to an actual durable receipt, not an exception message or user payload. */
    record Receipt(String idempotencyKey,ActionExecutionContract.Outcome outcome,String evidenceReference,
                   String resultReference,String errorCode) {
        public Receipt {
            if(idempotencyKey==null || !idempotencyKey.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Action receipt identity required");
            Objects.requireNonNull(outcome);
            if(evidenceReference!=null)reference(evidenceReference,128);
            if(resultReference!=null)reference(resultReference,128);
            if(outcome!=ActionExecutionContract.Outcome.UNKNOWN && evidenceReference==null)
                throw new IllegalArgumentException("Confirmed action outcome requires receipt evidence");
            if(outcome==ActionExecutionContract.Outcome.CONFIRMED_SUCCESS) {
                if(resultReference==null || errorCode!=null)
                    throw new IllegalArgumentException("Successful action requires result without error");
            } else if(errorCode==null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}"))
                throw new IllegalArgumentException("Action failure requires a bounded error code");
        }
        public void requireRequest(Request request) {
            if(request==null || !Objects.equals(idempotencyKey,request.idempotencyKey()))
                throw new IllegalArgumentException("Action receipt belongs to another request");
        }
    }
    Definition definition();
    /** Existing owning-operation grants, chosen by the server implementation. */
    default java.util.Set<String> requiredResources(){return java.util.Set.of();}
    /** Pure local configuration validation; resolve secrets and authorize separately before execution. */
    void validateConfiguration(JsonNode configuration);
    /** Called after claim commit, outside a database transaction. Ambiguous exceptions mean UNKNOWN. */
    Receipt execute(Request request);

    private static void reference(String value,int maximum) {
        if(value==null || value.length()>maximum || !value.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]*"))
            throw new IllegalArgumentException("Invalid action adapter reference");
    }
}
