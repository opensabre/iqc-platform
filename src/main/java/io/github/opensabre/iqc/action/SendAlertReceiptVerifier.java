package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.shared.IqcNotificationClient;
import java.util.Objects;

/** Checks owning-service durable evidence against the frozen declaration before accepting success. */
public final class SendAlertReceiptVerifier {
    private SendAlertReceiptVerifier() { }

    /** Missing, ambiguous or mismatched evidence never authorizes a resend or confirms success. */
    public static ActionAdapter.Receipt verify(String requestId, SendAlertConfiguration configuration,
                                               IqcNotificationClient.Status status) {
        if (requestId == null || !requestId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid alert request identity");
        Objects.requireNonNull(configuration);
        if (status != null && "iqc-notification-status-v1".equals(status.schemaVersion())
                && requestId.equals(status.requestId())
                && configuration.declaredHash().equals(status.declaredRequestHash())
                && status.recordId() != null && status.recordId().matches("[a-f0-9]{32}")
                && status.requestHash() != null && status.requestHash().matches("[a-f0-9]{64}")
                && "SUCCESS".equals(status.status())
                && status.messageId() != null && !status.messageId().isBlank()
                && status.messageId().length() <= 128
                && status.messageId().codePoints().noneMatch(Character::isISOControl)) {
            // Persist only the owning record reference; provider identifiers may contain arbitrary text.
            return new ActionAdapter.Receipt(requestId, ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,
                    status.recordId(), status.recordId(), null);
        }
        return new ActionAdapter.Receipt(requestId, ActionExecutionContract.Outcome.UNKNOWN,
                null, null, "ALERT_CONFIRMATION_UNAVAILABLE");
    }
}
