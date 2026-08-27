package io.github.opensabre.iqc;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.audit.annotations.EnabledAudit;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import io.github.opensabre.iqc.conversation.ConversationUploadProperties;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@EnabledAudit
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({LlmQualityProperties.class, ConversationUploadProperties.class})
@EnableMethodCache(basePackages = "io.github.opensabre.iqc")
@EnableFeignClients(clients = IqcOrganizationClient.class)
public class IqcPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(IqcPlatformApplication.class, args);
    }

    @Bean(name = "iqcTaskExecutor")
    public ThreadPoolTaskExecutor iqcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("iqc-task-");
        executor.initialize();
        return executor;
    }

    @Bean
    public ObjectMapper iqcObjectMapper() {
        return new ObjectMapper();
    }
}
