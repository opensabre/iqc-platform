package io.github.opensabre.iqc;

import io.github.opensabre.governance.errorcatalog.ErrorCatalogProvider;
import io.github.opensabre.governance.audit.aspect.AuditAspect;
import io.github.opensabre.governance.dictionary.DictionaryProvider;
import io.github.opensabre.governance.registration.GovernanceRegistrationEndpoint;
import io.github.opensabre.iqc.result.llm.SpringAiLlmQualityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
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
        "jetcache.remote.default.type=mock",
        "jetcache.remote.longTime.type=mock",
        "jetcache.remote.shortTime.type=mock"
})
class IqcPlatformApplicationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void loadsWithOpenSabreGovernanceAndSafeLlmDefault() {
        assertThat(applicationContext.getBeansOfType(ErrorCatalogProvider.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(DictionaryProvider.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(AuditAspect.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(GovernanceRegistrationEndpoint.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(SpringAiLlmQualityProvider.class)).hasSize(1);
    }
}
