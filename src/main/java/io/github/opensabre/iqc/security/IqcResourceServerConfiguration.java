package io.github.opensabre.iqc.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.stream.Stream;

/**
 * Verifies the external access token relayed by the gateway.
 *
 * <p>The gateway keeps the browser session, while IQC still needs the verified JWT
 * in its own Spring Security context so data-scope checks can see roles such as ADMIN.</p>
 */
@Configuration(proxyBeanMethods = false)
public class IqcResourceServerConfiguration {

    @Bean
    SecurityFilterChain iqcResourceServerFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter iqcJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roleAuthorities = new JwtGrantedAuthoritiesConverter();
        roleAuthorities.setAuthoritiesClaimName("roles");
        roleAuthorities.setAuthorityPrefix("");
        JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> Stream.concat(
                        roleAuthorities.convert(jwt).stream(), scopeAuthorities.convert(jwt).stream())
                .toList());
        return converter;
    }
}
