package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.shared.IqcNotificationClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SendAlertReceiptVerifierTest {
    private final String key = "a".repeat(64);
    private final String record = "b".repeat(32);
    private final String contentHash = "c".repeat(64);
    private final SendAlertConfiguration config = new SendAlertConfiguration("IQC_ALERT", "EMAIL",
            "test@example.invalid", Map.of("reason", "risk"));

    @Test void acceptsDurableSuccessForExactlyTheFrozenDeclaration() {
        var receipt = SendAlertReceiptVerifier.verify(key, config, status("iqc-notification-status-v1", key,
                record, contentHash, config.declaredHash(), "SUCCESS", "provider receipt/42"));
        assertThat(receipt.outcome()).isEqualTo(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS);
        assertThat(receipt.evidenceReference()).isEqualTo(record);
        assertThat(receipt.resultReference()).isEqualTo(record);
    }

    @Test void rejectsEachMissingOrMismatchedProofWithoutConfirmedFailure() {
        var valid = new String[]{"iqc-notification-status-v1", key, record, contentHash,
                config.declaredHash(), "SUCCESS", "receipt-42"};
        for (int index = 0; index < valid.length; index++) {
            // Provider receipt is opaque: a different nonblank string is still valid evidence.
            for (String replacement : index == 6 ? new String[]{null, "", " "} : new String[]{null, "", "wrong"}) {
                var fields = valid.clone(); fields[index] = replacement;
                assertUnknown(status(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6]));
            }
        }
        assertUnknown(null);
        for (String state : new String[]{"STARTED", "UNKNOWN", "FAILED"})
            assertUnknown(status(valid[0], key, record, contentHash, valid[4], state, "receipt"));
        assertUnknown(status(valid[0], key, record, contentHash, valid[4], "SUCCESS", "x".repeat(129)));
        assertUnknown(status(valid[0], key, record, contentHash, valid[4], "SUCCESS", "receipt\n42"));
    }

    @Test void successForAnotherRecipientCannotSettleThisAction() {
        var other = new SendAlertConfiguration(config.sceneCode(), config.channel(), "other@example.invalid", config.args());
        assertUnknown(status("iqc-notification-status-v1", key, record, contentHash, other.declaredHash(), "SUCCESS", "receipt"));
    }

    private void assertUnknown(IqcNotificationClient.Status status) {
        var receipt = SendAlertReceiptVerifier.verify(key, config, status);
        assertThat(receipt.outcome()).isEqualTo(ActionExecutionContract.Outcome.UNKNOWN);
        assertThat(receipt.evidenceReference()).isNull();
        assertThat(receipt.resultReference()).isNull();
    }
    private IqcNotificationClient.Status status(String schema, String key, String record, String hash,
                                                String declared, String state, String message) {
        return new IqcNotificationClient.Status(schema, key, record, hash, declared, state, message);
    }
}
