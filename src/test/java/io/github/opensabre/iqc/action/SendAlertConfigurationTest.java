package io.github.opensabre.iqc.action;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class SendAlertConfigurationTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void independentDeclaredFingerprintMatchesNotificationUnicodeGolden() throws Exception {
        var value=SendAlertConfiguration.parse(json.readTree("""
            {"sceneCode":"IQC_ALERT","channel":"EMAIL","target":"test@example.invalid","args":{"reason":"高风险"}}
            """));
        assertThat(value.declaredHash()).isEqualTo("fc49ce9b81adf7d9a18763fc1f219d989c9a6f1e1f6a07d153dec13d1b120eb0");
        assertThat(value.send().target()).isEqualTo("test@example.invalid");
        assertThatThrownBy(()->value.args().put("extra","x")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsUnboundedOrAmbiguousConfiguration() throws Exception {
        for(String config:new String[]{
            "{\"sceneCode\":\"IQC_ALERT\",\"channel\":\"AUTO\",\"target\":\"x@example.invalid\"}",
            "{\"sceneCode\":\"IQC_ALERT\",\"channel\":\"EMAIL\",\"target\":\"x@example.invalid\",\"args\":{\"risk\":1}}",
            "{\"sceneCode\":\"IQC_ALERT\",\"channel\":\"EMAIL\",\"target\":\"x@example.invalid\",\"callback\":\"https://example.invalid\"}",
            "{\"sceneCode\":\"IQC_ALERT\",\"channel\":\"SMS\",\"target\":\"not-a-phone\"}"}) {
            var node=json.readTree(config);
            assertThatThrownBy(()->SendAlertConfiguration.parse(node)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
