package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider-local readers must preserve decimal confidence before any tree rewrite. */
class ConfidenceDecimalParsingTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpLlmQualityProvider http=new HttpLlmQualityProvider(json,mock(GovernanceRateLimiter.class),mock(UsageCounterRecorder.class),new LlmQualityProperties());
    private final SpringAiLlmQualityProvider spring=new SpringAiLlmQualityProvider(mock(ChatModel.class),null,json,mock(GovernanceRateLimiter.class),mock(UsageCounterRecorder.class),new LlmQualityProperties());
    private String payload(String confidence){return "{\"hit\":true,\"reason\":\"measured\""+(confidence==null?"":",\"confidence\":"+confidence)+"}";}
    private JsonNode httpBody(String payload,boolean textual) throws Exception {
        String body="{\"choices\":[{\"message\":{\"content\":"+(textual?json.writeValueAsString(payload):payload)+"}}]}";
        return ReflectionTestUtils.invokeMethod(http,"parseResponseBody",body);
    }
    @Test void bothHttpContentFormsAndSpringAiPreserveExactBoundaryAndReportedValue() throws Exception {
        for(String value:List.of("0.29999999999999999","0.3","0","1")) {
            var expected=new BigDecimal(value);
            for(boolean textual:List.of(true,false)) {
                var result=http.parseStructuredResponse(httpBody(payload(value),textual));
                assertThat(result.path("confidence").decimalValue()).isEqualByComparingTo(expected);
                LlmQualityProvider.LlmEvaluation evaluation=ReflectionTestUtils.invokeMethod(http,"parseEvaluation",httpBody(payload(value),textual));
                assertThat(evaluation.reportedConfidence(json)).isEqualByComparingTo(expected);
                assertThat(evaluation.validStructuredOutput(json,false)).isTrue();
            }
            var result=spring.parseEvaluation(payload(value));
            assertThat(result.reportedConfidence(json)).isEqualByComparingTo(expected);
            assertThat(result.validStructuredOutput(json,false)).isTrue();
        }
        var below=spring.parseEvaluation(payload("0.29999999999999999")).reportedConfidence(json);
        var boundary=spring.parseEvaluation(payload("0.3")).reportedConfidence(json);
        assertThat(below.multiply(BigDecimal.TEN).intValue()).isEqualTo(2);
        assertThat(boundary.multiply(BigDecimal.TEN).intValue()).isEqualTo(3);
    }
    @Test void tinyOutOfRangeConfidenceIsRejectedRatherThanRoundedIntoRange() throws Exception {
        for(String value:List.of("1.00000000000000001","-0.00000000000000001","\"0.3\"")) {
            assertThatThrownBy(()->spring.parseEvaluation(payload(value))).isInstanceOf(IllegalArgumentException.class);
            for(boolean textual:List.of(true,false))assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(http,"parseEvaluation",httpBody(payload(value),textual)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for(String value:new String[]{null,"null"}) {
            var evaluation=spring.parseEvaluation(payload(value));assertThat(evaluation.reportedConfidence(json)).isNull();
            assertThat(evaluation.validStructuredOutput(json,false)).isTrue();
        }
    }
    @Test void duplicateKeysAndTrailingDocumentsCannotBecomeValidMeasurements() throws Exception {
        for(String invalid:List.of(payload("0.3").replace("}",",\"confidence\":0.4}"),payload("0.3").replace("}",",\"hit\":false}"),payload("0.3")+" {}")) {
            assertThatThrownBy(()->spring.parseEvaluation(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->http.parseStructuredResponse(httpBody(invalid,true))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->httpBody(invalid,false)).isInstanceOf(IllegalArgumentException.class);
            var evaluation=new LlmQualityProvider.LlmEvaluation(true,true,"measured",invalid);
            assertThat(evaluation.reportedConfidence(json)).isNull();assertThat(evaluation.validStructuredOutput(json,false)).isFalse();
        }
    }
}
