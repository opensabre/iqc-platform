package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.result.llm.SecretReferenceResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in deployment integration; the original action dispatcher remains controlled separately. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="iqc.actions.external.enabled",havingValue="true",matchIfMissing=false)
@EnableConfigurationProperties(ExternalActionProperties.class)
public class ExternalActionBeans {
    @Bean ExternalActionDestinations externalActionDestinations(ExternalActionProperties properties) {
        return new ExternalActionDestinations(properties.destinations());
    }
    @Bean HttpExternalActionTransport httpExternalActionTransport(SecretReferenceResolver secrets) { return new HttpExternalActionTransport(secrets); }
    @Bean ExternalActionAdapter webhookActionAdapter(ExternalActionPreparationService preparation,
            ExternalActionDestinations destinations,HttpExternalActionTransport transport,IqcDataScope scope) {
        return new ExternalActionAdapter(ActionExecutionContract.Type.WEBHOOK,preparation,destinations,transport,scope);
    }
    @Bean ExternalActionAdapter workOrderActionAdapter(ExternalActionPreparationService preparation,
            ExternalActionDestinations destinations,HttpExternalActionTransport transport,IqcDataScope scope) {
        return new ExternalActionAdapter(ActionExecutionContract.Type.CREATE_WORK_ORDER,preparation,destinations,transport,scope);
    }
    @Bean ExternalActionAdapter coachingTaskActionAdapter(ExternalActionPreparationService preparation,
            ExternalActionDestinations destinations,HttpExternalActionTransport transport,IqcDataScope scope) {
        return new ExternalActionAdapter(ActionExecutionContract.Type.CREATE_COACHING_TASK,preparation,destinations,transport,scope);
    }
}
