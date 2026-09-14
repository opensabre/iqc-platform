package io.github.opensabre.iqc;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import io.github.opensabre.governance.errorcatalog.ErrorCatalogProvider;
import io.github.opensabre.governance.audit.aspect.AuditAspect;
import io.github.opensabre.governance.dictionary.DictionaryProvider;
import io.github.opensabre.governance.registration.GovernanceRegistrationEndpoint;
import io.github.opensabre.iqc.result.llm.SpringAiLlmQualityProvider;
import io.github.opensabre.security.actuator.ActuatorMonitoringTokenIssuer;
import io.github.opensabre.security.token.InternalTokenConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.config.import=optional:",
        "spring.datasource.url=jdbc:h2:mem:iqc_context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "iqc.task.scheduler-enabled=false",
        "opensabre.governance.dictionary.registration-enabled=false",
        "opensabre.governance.error-catalog.enabled=false",
        "opensabre.resource-registration.enabled=false",
        "opensabre.security.internal-token.enabled=true",
        "opensabre.security.internal-token.active-key-id=test-key",
        "opensabre.security.internal-token.active-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "opensabre.security.internal-token.allowed-issuers[0]=iqc-platform",
        "jetcache.remote.default.type=mock",
        "jetcache.remote.longTime.type=mock",
        "jetcache.remote.shortTime.type=mock"
})
@Import(IqcPlatformApplicationTest.InternalTokenNacosTestConfiguration.class)
class IqcPlatformApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ActuatorMonitoringTokenIssuer actuatorMonitoringTokenIssuer;

    @Test
    void loadsWithOpenSabreGovernanceAndSafeLlmDefault() {
        assertThat(applicationContext.getBeansOfType(ErrorCatalogProvider.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(DictionaryProvider.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(AuditAspect.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(GovernanceRegistrationEndpoint.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(SpringAiLlmQualityProvider.class)).hasSize(1);
    }

    @Test
    void exposesOpenApiDocumentForGatewayDiscoveryWithoutAuthentication() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void protectsManagementMetricsWithActuatorInternalToken() throws Exception {
        URI metric = URI.create("http://localhost:" + managementPort
                + "/actuator/metrics/process.uptime");
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<Void> anonymous = client.send(
                HttpRequest.newBuilder(metric).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        String token = actuatorMonitoringTokenIssuer.issue("iqc-platform");
        HttpResponse<Void> authorized = client.send(
                HttpRequest.newBuilder(metric)
                        .header(InternalTokenConstants.HEADER, token)
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(authorized.statusCode()).isEqualTo(200);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InternalTokenNacosTestConfiguration {

        @Bean
        NacosConfigManager nacosConfigManager() {
            ConfigService configService = mock(ConfigService.class);
            NacosConfigManager manager = mock(NacosConfigManager.class);
            when(manager.getConfigService()).thenReturn(configService);
            return manager;
        }
    }
}
