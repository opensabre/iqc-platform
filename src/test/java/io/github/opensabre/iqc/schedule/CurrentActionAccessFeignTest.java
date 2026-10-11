package io.github.opensabre.iqc.schedule;

import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Target;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.shared.IqcOrganizationClient;
import io.github.opensabre.rpc.openfeign.interceptor.FeignInternalTokenInterceptor;
import io.github.opensabre.security.config.InternalTokenProperties;
import io.github.opensabre.security.context.InternalTokenUserContext;
import io.github.opensabre.security.token.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production Feign interface/interceptor and loopback HTTP; organization database is not involved. */
class CurrentActionAccessFeignTest {
    @Test void currentAccessUsesUserHopAndRemovesCallerCredentialHeaders() throws Exception {
        var properties=new InternalTokenProperties();properties.setEnabled(true);
        properties.setActiveKeyId("test");properties.setActiveKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.setAllowedIssuers(Set.of("base-gateway","iqc-platform"));
        var securityJson=new tools.jackson.databind.ObjectMapper();
        var signing=new DefaultInternalTokenService(securityJson,properties);
        var user=new InternalTokenUserContext(securityJson);
        var inbound=signing.issue(new InternalTokenRequest("base-gateway","user","alice","iqc-platform",List.of(),List.of(),1,null,null,Map.of()));
        var verified=signing.verify(inbound,"iqc-platform");
        var received=new AtomicReference<InternalTokenClaims>();var failure=new AtomicReference<Throwable>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{
                assertEquals("GET",exchange.getRequestMethod());
                assertEquals("/internal/iqc/scheduler-access/current/user",exchange.getRequestURI().getPath());
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                assertNull(exchange.getRequestHeaders().getFirst("x-client-token-user"));
                var claims=signing.verify(exchange.getRequestHeaders().getFirst(InternalTokenConstants.HEADER),"base-organization");
                received.set(claims);
                byte[] body=("{\"data\":{\"schemaVersion\":\"iqc-scheduler-access-v1\",\"userId\":\"user\",\"username\":\"alice\",\"accountValid\":true,\"groupId\":\"group\",\"roles\":[],\"resourceCodes\":[\"iqc:action:recover\"],\"checkedAt\":\"2026-10-11T00:00:00Z\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            }catch(Throwable problem){failure.set(problem);exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });
        server.start();
        try{
            user.bind(verified);
            var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var client=Feign.builder().contract(new SpringMvcContract())
                .requestInterceptor(template->{template.header("Authorization","Bearer caller");template.header("x-client-token-user","forged");template.header(InternalTokenConstants.HEADER,inbound);})
                .requestInterceptor(new FeignInternalTokenInterceptor(signing,new InternalTokenRequestFactory(user),properties,"iqc-platform"))
                .decoder((response,type)->Result.success(json.treeToValue(json.readTree(response.body().asReader(StandardCharsets.UTF_8)).path("data"),IqcOrganizationClient.SchedulerAccess.class)))
                .target(new Target.HardCodedTarget<>(IqcOrganizationClient.class,"base-organization","http://127.0.0.1:"+server.getAddress().getPort()));
            var result=client.currentActionAccess("user").getData();
            assertNull(failure.get());assertEquals("user",result.userId());assertEquals("alice",result.username());
            var next=received.get();assertNotNull(next);assertEquals("user",next.subject());assertEquals("alice",next.username());
            assertEquals("iqc-platform",next.issuer());assertEquals(2,next.hop());
            assertEquals(verified.tokenId(),next.parentTokenId());assertNotEquals(verified.tokenId(),next.tokenId());
            assertSame(verified,user.currentClaims());
        }finally{user.clear();server.stop(0);}
    }
}
