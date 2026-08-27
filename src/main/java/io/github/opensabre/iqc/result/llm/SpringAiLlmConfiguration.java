package io.github.opensabre.iqc.result.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Creates the Spring AI 2 runtime without coupling IQC domain services to a model vendor. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${iqc.llm.enabled:false}' == 'true' && '${iqc.llm.provider:spring-ai}' == 'spring-ai'")
public class SpringAiLlmConfiguration {

    /** Builds an OpenAI-compatible model; DashScope and private gateways use the same protocol. */
    @Bean
    ChatModel iqcChatModel(LlmQualityProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            throw new IllegalStateException("IQC Spring AI endpoint is missing");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("IQC Spring AI API key is missing");
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalStateException("IQC Spring AI model is missing");
        }
        ResponseFormat responseFormat = ResponseFormat.builder().type(Type.JSON_OBJECT).build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(properties.getEndpoint())
                .apiKey(properties.getApiKey())
                .model(properties.getModel())
                .temperature(0.0)
                .responseFormat(responseFormat)
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder().options(options).build();
    }
}
