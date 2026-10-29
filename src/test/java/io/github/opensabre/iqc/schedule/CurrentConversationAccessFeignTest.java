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

/** Real application access chain and signed Feign HTTP; server responses are fixtures, not organization SQL/PDP. */
class CurrentConversationAccessFeignTest {
    @Test void conversationReadRevalidatesRevocationThroughSignedFeignHttp() throws Exception {
        var properties=new InternalTokenProperties();properties.setEnabled(true);
        properties.setActiveKeyId("test");properties.setActiveKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.setAllowedIssuers(Set.of("base-gateway","iqc-platform"));
        var securityJson=new tools.jackson.databind.ObjectMapper();
        var signing=new DefaultInternalTokenService(securityJson,properties);
        var user=new InternalTokenUserContext(securityJson);
        var inbound=signing.issue(new InternalTokenRequest("base-gateway","user","alice","iqc-platform",List.of(),List.of(),1,null,null,Map.of()));
        var verified=signing.verify(inbound,"iqc-platform");
        var received=new AtomicReference<InternalTokenClaims>();var failure=new AtomicReference<Throwable>();
        var grants=new AtomicReference<>(Set.of("iqc:conversation:api-stats:view"));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{
                assertEquals("GET",exchange.getRequestMethod());
                String path=exchange.getRequestURI().getPath();
                assertTrue(Set.of("/user","/internal/iqc/scheduler-access/current/user").contains(path));
                if(path.equals("/user"))assertEquals("uniqueId=alice",exchange.getRequestURI().getQuery());
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                assertNull(exchange.getRequestHeaders().getFirst("x-client-token-user"));
                var claims=signing.verify(exchange.getRequestHeaders().getFirst(InternalTokenConstants.HEADER),"base-organization");
                received.set(claims);
                String data=path.equals("/user")
                    ? "{\"id\":\"user\",\"username\":\"alice\",\"groupId\":\"group\"}"
                    : new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(
                        new IqcOrganizationClient.SchedulerAccess("iqc-scheduler-access-v1","user","alice",true,"group",Set.of("INSPECTOR"),grants.get(),java.time.Instant.now()));
                byte[] body=("{\"data\":"+data+"}").getBytes(StandardCharsets.UTF_8);
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
                .decoder((response,type)->Result.success(json.treeToValue(json.readTree(response.body().asReader(StandardCharsets.UTF_8)).path("data"),json.constructType(type.getTypeName().contains("OrganizationUser")?IqcOrganizationClient.OrganizationUser.class:IqcOrganizationClient.SchedulerAccess.class))))
                .target(new Target.HardCodedTarget<>(IqcOrganizationClient.class,"base-organization","http://127.0.0.1:"+server.getAddress().getPort()));
            var context=io.github.opensabre.common.core.util.UserContextHolder.getInstance();
            context.setContext(Map.of(context.KEY_USERNAME,"alice",context.KEY_USER_ID,"user",context.KEY_SCOPES,""));
            var scope=new io.github.opensabre.iqc.shared.IqcDataScope(client);
            var actors=new ScheduledActorAccessService(client,scope,user);
            var queries=org.mockito.Mockito.mock(io.github.opensabre.iqc.conversation.ConversationQueryService.class);
            org.mockito.Mockito.when(queries.apiStats(null,null)).thenReturn(Map.of("count",1));
            var controller=new io.github.opensabre.iqc.rest.ConversationQueryController(queries,new io.github.opensabre.iqc.privacy.SensitiveReadAccessService(actors));
            assertEquals(Map.of("count",1),controller.apiStats(null,null));
            org.mockito.Mockito.verify(queries).apiStats(null,null);org.mockito.Mockito.clearInvocations(queries);
            grants.set(Set.of("iqc:conversation:view"));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->controller.apiStats(null,null));
            org.mockito.Mockito.verifyNoInteractions(queries);assertNull(failure.get());
            var next=received.get();assertNotNull(next);assertEquals("user",next.subject());assertEquals("alice",next.username());
            assertEquals("iqc-platform",next.issuer());assertEquals(2,next.hop());
            assertEquals(verified.tokenId(),next.parentTokenId());assertNotEquals(verified.tokenId(),next.tokenId());
            assertSame(verified,user.currentClaims());
        }finally{user.clear();io.github.opensabre.common.core.util.UserContextHolder.getInstance().clear();server.stop(0);}
    }
}
