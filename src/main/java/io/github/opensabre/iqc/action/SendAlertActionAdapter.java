package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcNotificationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.Set;

/** Executes the owning notification operation after local proof commits; recovery only performs GET. */
@Component @RequiredArgsConstructor
public class SendAlertActionAdapter implements ActionAdapter {
    private final SendAlertPreparationService preparation;
    private final IqcNotificationClient notifications;
    private final IqcDataScope scope;
    @Override public Definition definition() { return SendAlertPreparationService.DEFINITION; }
    @Override public Set<String> requiredResources() { return SendAlertPreparationService.RESOURCES; }
    @Override public void validateConfiguration(JsonNode configuration) { SendAlertConfiguration.parse(configuration); }

    /** NEVER also rejects direct adapter calls made inside an analysis transaction. */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Receipt execute(Request request) {
        requireRequest(request);
        try {
            var configuration = preparation.prepareSend(request);
            // Preparation returned through its Spring proxy, so its source locks are already released.
            scope.requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
            scope.requireScheduledResources(requiredResources());
            var response = notifications.send(request.idempotencyKey(), configuration.send());
            return SendAlertReceiptVerifier.verify(request.idempotencyKey(), configuration,
                    response != null && response.isSuccess() ? response.getData() : null);
        } catch (Exception unavailable) {
            return unknown(request);
        }
    }

    /** No POST fallback after missing evidence, timeout, authorization failure or UNKNOWN state. */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Receipt reconcile(Request request) {
        requireRequest(request);
        try {
            var configuration = preparation.prepareLookup(request);
            var response = notifications.lookup(request.idempotencyKey());
            return SendAlertReceiptVerifier.verify(request.idempotencyKey(), configuration,
                    response != null && response.isSuccess() ? response.getData() : null);
        } catch (Exception unavailable) {
            return unknown(request);
        }
    }
    private void requireRequest(Request request) {
        Objects.requireNonNull(request);
        if (!definition().equals(request.adapter())) throw new IllegalArgumentException("SEND_ALERT adapter version mismatch");
    }
    private Receipt unknown(Request request) {
        return new Receipt(request.idempotencyKey(), ActionExecutionContract.Outcome.UNKNOWN,
                null, null, "ALERT_CONFIRMATION_UNAVAILABLE");
    }
}
