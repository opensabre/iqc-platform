package io.github.opensabre.iqc.action;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.RequestInterceptor;
import feign.Target;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.shared.IqcNotificationClient;
import io.github.opensabre.iqc.shared.IqcNotificationFeignConfiguration;
import io.github.opensabre.rpc.openfeign.interceptor.FeignInternalTokenInterceptor;
import io.github.opensabre.security.config.InternalTokenProperties;
import io.github.opensabre.security.context.InternalTokenUserContext;
import io.github.opensabre.security.token.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.FeignClientSpecification;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Feign child-context override and signed loopback HTTP; no notification provider or database. */
class SendAlertFeignIdentityTest {
    @Test void sendAndLookupUseServiceIdentityWithoutChangingTheInteractiveCaller() throws Exception {
        var properties = new InternalTokenProperties(); properties.setEnabled(true);
        properties.setActiveKeyId("test"); properties.setActiveKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.setAllowedIssuers(Set.of("base-gateway", "iqc-platform"));
        var securityJson = new tools.jackson.databind.ObjectMapper();
        var signing = new DefaultInternalTokenService(securityJson, properties);
        var users = new InternalTokenUserContext(securityJson);
        var inbound = signing.issue(new InternalTokenRequest("base-gateway", "user", "alice", "iqc-platform",
                List.of("iqc:result:all"), List.of("IQC_ADMIN"), 1, null, null, Map.of()));
        var originalClaims = signing.verify(inbound, "iqc-platform");
        var parent = new AnnotationConfigApplicationContext();
        parent.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("spring.application.name", "iqc-platform")));
        parent.registerBean(InternalTokenProperties.class, () -> properties);
        parent.registerBean(InternalTokenService.class, () -> signing);
        parent.registerBean(InternalTokenUserContext.class, () -> users);
        var inherited = new FeignInternalTokenInterceptor(signing, new InternalTokenRequestFactory(users), properties, "iqc-platform");
        parent.registerBean("feignInternalTokenInterceptor", FeignInternalTokenInterceptor.class, () -> inherited);
        parent.refresh();
        var factory = new FeignClientFactory(); factory.setApplicationContext(parent);
        factory.setConfigurations(List.of(new FeignClientSpecification("base-sysadmin", IqcNotificationClient.class.getName(),
                new Class<?>[]{IqcNotificationFeignConfiguration.class})));
        var failure = new AtomicReference<Throwable>(); var calls = new AtomicInteger();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var key = "a".repeat(64);
        var declaration = new SendAlertConfiguration("IQC_ALERT", "EMAIL", "test@example.invalid", Map.of("reason", "risk"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                assertEquals("/internal/iqc/notifications/" + key, exchange.getRequestURI().getPath());
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                assertNull(exchange.getRequestHeaders().getFirst("x-client-token-user"));
                var claims = signing.verify(exchange.getRequestHeaders().getFirst(InternalTokenConstants.HEADER), "base-sysadmin");
                assertEquals("service:iqc-platform", claims.subject()); assertEquals("iqc-platform", claims.issuer());
                assertEquals("iqc-platform", claims.source()); assertEquals("base-sysadmin", claims.destination());
                assertEquals(1, claims.hop()); assertNull(claims.parentTokenId());
                assertTrue(claims.roles().isEmpty()); assertTrue(claims.scopes().isEmpty()); assertTrue(claims.authorities().isEmpty());
                int number = calls.incrementAndGet();
                assertEquals(number == 1 ? "POST" : "GET", exchange.getRequestMethod());
                if (number == 1) assertEquals(json.valueToTree(declaration.send()), json.readTree(exchange.getRequestBody()));
                else assertEquals(0, exchange.getRequestBody().readAllBytes().length);
                byte[] response = json.writeValueAsBytes(Map.of("data", new IqcNotificationClient.Status(
                        "iqc-notification-status-v1", key, "b".repeat(32), "c".repeat(64), declaration.declaredHash(), "SUCCESS", "receipt")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable problem) { failure.set(problem); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            users.bind(originalClaims);
            var originalContext = new HashMap<>(UserContextHolder.getInstance().getContext());
            var interceptors = factory.getInstances("base-sysadmin", RequestInterceptor.class);
            assertEquals(1, interceptors.size());
            assertNotSame(inherited, interceptors.get("feignInternalTokenInterceptor"));
            var builder = Feign.builder().contract(new SpringMvcContract())
                    .requestInterceptor(template -> {template.header("Authorization", "Bearer caller"); template.header("x-client-token-user", "forged");})
                    .encoder((body, type, template) -> {
                        try { template.body(json.writeValueAsBytes(body), StandardCharsets.UTF_8); template.header("Content-Type", "application/json"); }
                        catch (com.fasterxml.jackson.core.JsonProcessingException failureToEncode) {
                            throw new feign.codec.EncodeException("Cannot encode notification declaration", failureToEncode);
                        }
                    })
                    .decoder((response, type) -> Result.success(json.treeToValue(json.readTree(response.body().asReader(StandardCharsets.UTF_8)).path("data"), IqcNotificationClient.Status.class)));
            interceptors.values().forEach(builder::requestInterceptor);
            var client = builder.target(new Target.HardCodedTarget<>(IqcNotificationClient.class, "base-sysadmin", "http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals("SUCCESS", client.send(key, declaration.send()).getData().status());
            assertEquals("SUCCESS", client.lookup(key).getData().status());
            assertNull(failure.get()); assertEquals(2, calls.get());
            assertSame(originalClaims, users.currentClaims());
            assertEquals(originalContext, UserContextHolder.getInstance().getContext());
        } finally { users.clear(); server.stop(0); factory.destroy(); parent.close(); }
    }
}
