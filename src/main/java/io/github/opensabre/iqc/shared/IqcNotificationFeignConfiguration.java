package io.github.opensabre.iqc.shared;

import feign.RequestTemplate;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.rpc.openfeign.interceptor.FeignInternalTokenInterceptor;
import io.github.opensabre.security.config.InternalTokenProperties;
import io.github.opensabre.security.context.InternalTokenUserContext;
import io.github.opensabre.security.token.InternalTokenRequest;
import io.github.opensabre.security.token.InternalTokenRequestFactory;
import io.github.opensabre.security.token.InternalTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import java.util.List;
import java.util.Map;

/** Feign child-context configuration only; deliberately not component-scanned into other clients. */
public class IqcNotificationFeignConfiguration {
    /** Shadows the inherited interceptor by name while reusing its credential stripping and signing. */
    @Bean
    public FeignInternalTokenInterceptor feignInternalTokenInterceptor(InternalTokenService signing,
            InternalTokenUserContext users, InternalTokenProperties properties,
            @Value("${spring.application.name:}") String applicationName) {
        var serviceRequests = new InternalTokenRequestFactory(users) {
            @Override public InternalTokenRequest create(String issuer, String audience) {
                if (!"iqc-platform".equals(issuer) || !"base-sysadmin".equals(audience))
                    throw new IllegalStateException("Notification service signing target mismatch");
                return new InternalTokenRequest(issuer, "service:" + issuer, null, audience,
                        List.of(), List.of(), List.of(), 1, null,
                        UserContextHolder.getInstance().getValue("trace_id"), Map.of());
            }
        };
        return new FeignInternalTokenInterceptor(signing, serviceRequests, properties, applicationName) {
            @Override public void apply(RequestTemplate template) {
                if (!properties.isEnabled()) throw new IllegalStateException("Notification service signing is required");
                super.apply(template);
            }
        };
    }
}
