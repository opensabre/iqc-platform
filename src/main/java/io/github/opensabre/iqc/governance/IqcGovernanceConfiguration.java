package io.github.opensabre.iqc.governance;

import io.github.opensabre.governance.errorcatalog.ErrorCatalogProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IqcGovernanceConfiguration {

    @Bean
    ErrorCatalogProvider iqcErrorCatalogProvider() {
        return ErrorCatalogProvider.of("iqc", IqcErrorType.values());
    }
}
