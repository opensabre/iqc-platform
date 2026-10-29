package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Set;

/** Binds remote durable evidence to the original frozen action identity and input digest. */
public final class ExternalActionReceiptVerifier {
    public static final String SCHEMA = "iqc-external-action-receipt-v1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "idempotencyKey", "inputSnapshotHash",
            "outcome", "evidenceReference", "resultReference", "errorCode");
    private ExternalActionReceiptVerifier() { }

    /** HTTP success alone is insufficient; malformed or unrelated evidence stays UNKNOWN. */
    public static ActionAdapter.Receipt verify(ActionAdapter.Request request, JsonNode response) {
        Objects.requireNonNull(request);
        try {
            if (response == null || !response.isObject()) return unknown(request);
            var fields = response.fieldNames();
            while (fields.hasNext()) if (!FIELDS.contains(fields.next())) return unknown(request);
            if (!SCHEMA.equals(text(response, "schemaVersion"))
                    || !request.idempotencyKey().equals(text(response, "idempotencyKey"))
                    || !request.inputSnapshotHash().equals(text(response, "inputSnapshotHash"))) return unknown(request);
            var receipt = new ActionAdapter.Receipt(request.idempotencyKey(),
                    ActionExecutionContract.Outcome.valueOf(text(response, "outcome")),
                    text(response, "evidenceReference"), text(response, "resultReference"), text(response, "errorCode"));
            receipt.requireRequest(request);
            return receipt;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return unknown(request);
        }
    }

    /** A stable bounded code avoids persisting remote bodies or credential-bearing exceptions. */
    public static ActionAdapter.Receipt unknown(ActionAdapter.Request request) {
        return new ActionAdapter.Receipt(request.idempotencyKey(), ActionExecutionContract.Outcome.UNKNOWN,
                null, null, "EXTERNAL_CONFIRMATION_UNAVAILABLE");
    }

    private static String text(JsonNode value, String field) {
        var node = value.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("Receipt field must be text");
        return node.textValue();
    }
}
