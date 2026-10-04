package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider.LlmEvaluation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ItemCandidateOutputTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void explicitNoMatchDoesNotDependOnLegacyHitFlag() {
        var decoded = ItemCandidateOutput.decode(mapper, new LlmEvaluation(true, true, "legacy",
                "{\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"NO_MATCH\",\"quotes\":[]}"));
        assertThat(decoded.result().getResultStatus()).isEqualTo("NOT_HIT");
        assertThat(decoded.quotes()).isEmpty();
    }

    @Test
    void candidateRetainsIdentityAndOnlyQuotedTextWithoutDeduction() {
        var decoded = ItemCandidateOutput.decode(mapper, new LlmEvaluation(true, false, "legacy",
                "{\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"MATCH\",\"quotes\":["
                        + "{\"messageId\":\"m1\",\"conversationId\":\"c1\",\"speakerRole\":\"customer\",\"text\":\"想贷款\"}]}"));
        assertThat(decoded.result().getResultStatus()).isEqualTo("HIT");
        assertThat(decoded.result().getDeduction()).isZero();
        assertThat(decoded.quotes()).hasSize(1);
        assertThat(decoded.quotes().getFirst().getId()).isEqualTo("m1");
        assertThat(decoded.quotes().getFirst().getContent()).isEqualTo("想贷款");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "bad", "{\"hit\":false}",
            "{\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"UNKNOWN\",\"quotes\":[]}",
            "{\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"MATCH\",\"quotes\":[]}",
            "{\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"NO_MATCH\",\"quotes\":[{}]}",
            "{\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"MATCH\",\"quotes\":[{\"messageId\":1}]}"})
    void invalidOrUnknownOutputIsNeverClearance(String json) {
        var decoded = ItemCandidateOutput.decode(mapper, new LlmEvaluation(true, false, "legacy", json));
        assertThat(decoded.result().getResultStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(decoded.quotes()).isEmpty();
    }

    @Test
    void unsupportedOutputIsErrorWithoutRawProviderDetails() {
        var decoded = ItemCandidateOutput.decode(mapper, new LlmEvaluation(false, false, "secret"));
        assertThat(decoded.result().getResultStatus()).isEqualTo("ERROR");
        assertThat(decoded.result().getReason()).doesNotContain("secret");
    }
}
