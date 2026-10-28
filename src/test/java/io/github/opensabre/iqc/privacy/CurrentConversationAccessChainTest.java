package io.github.opensabre.iqc.privacy;

import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.conversation.ConversationQueryService;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rest.ConversationQueryController;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.security.context.InternalTokenUserContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real current-access application chain; organization responses remain controlled, not a PDP server. */
class CurrentConversationAccessChainTest {
    private static final String CODE="iqc:conversation:api-stats:view";
    private final IqcOrganizationClient organization=mock(IqcOrganizationClient.class);
    private final ConversationQueryService queries=mock(ConversationQueryService.class);
    private final UserContextHolder context=UserContextHolder.getInstance();
    private ConversationQueryController controller;
    private TestingAuthenticationToken authentication;
    @BeforeEach void setup(){
        context.clear();SecurityContextHolder.clearContext();
        context.setContext(Map.of(context.KEY_USERNAME,"owner",context.KEY_USER_ID,"user",context.KEY_SCOPES,""));
        authentication=new TestingAuthenticationToken("owner","unused");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(organization.getUserByUniqueId("owner")).thenReturn(Result.success(
            new IqcOrganizationClient.OrganizationUser("user","Owner","owner","group","Group")));
        var scope=new IqcDataScope(organization);
        var actors=new ScheduledActorAccessService(organization,scope,mock(InternalTokenUserContext.class));
        controller=new ConversationQueryController(queries,new SensitiveReadAccessService(actors));
        when(queries.apiStats(null,null)).thenReturn(Map.of("count",1));
        project("user","owner",true,"group",Set.of("INSPECTOR"),Set.of(CODE),Instant.now());
    }
    @AfterEach void cleanup(){context.clear();SecurityContextHolder.clearContext();}
    private void project(String id,String owner,boolean valid,String group,Set<String> roles,Set<String> grants,Instant checked){
        when(organization.currentActionAccess("user")).thenReturn(Result.success(new IqcOrganizationClient.SchedulerAccess(
            "iqc-scheduler-access-v1",id,owner,valid,group,roles,grants,checked)));
    }
    @Test void successfulReadThenRevokedGrantRechecksCurrentEvidenceBeforeNextQuery(){
        assertEquals(Map.of("count",1),controller.apiStats(null,null));
        verify(queries).apiStats(null,null);clearInvocations(queries);
        project("user","owner",true,"group",Set.of("INSPECTOR"),Set.of("iqc:conversation:view"),Instant.now());
        assertThrows(IqcException.class,()->controller.apiStats(null,null));verifyNoInteractions(queries);
        verify(organization,times(2)).currentActionAccess("user");
        verify(organization,never()).schedulerAccess(anyString());
        assertSame(authentication,SecurityContextHolder.getContext().getAuthentication());assertEquals("owner",context.getUsername());
    }
    @ParameterizedTest @ValueSource(strings={"old-code","admin-without-base","stale","future","disabled","other-id","renamed","changed-group","lost-all","missing-response"})
    void invalidCurrentEvidenceNeverReachesConversationQuery(String scenario){
        String id="user",owner="owner",group="group";boolean valid=true;
        Set<String> roles=Set.of("INSPECTOR"),grants=Set.of(CODE);Instant checked=Instant.now();
        switch(scenario){
            case "old-code" -> grants=Set.of("iqc:conversation:view");
            case "admin-without-base" -> {roles=Set.of("ADMIN");grants=Set.of();}
            case "stale" -> checked=Instant.now().minusSeconds(60);
            case "future" -> checked=Instant.now().plusSeconds(60);
            case "disabled" -> valid=false;
            case "other-id" -> id="other";
            case "renamed" -> owner="renamed";
            case "changed-group" -> group="other";
            case "lost-all" -> context.setContext(Map.of(context.KEY_USERNAME,"owner",context.KEY_USER_ID,"user",context.KEY_SCOPES,"iqc:result:all"));
            case "missing-response" -> { }
            default -> throw new AssertionError(scenario);
        }
        project(id,owner,valid,group,roles,grants,checked);
        if(scenario.equals("missing-response"))when(organization.currentActionAccess("user")).thenReturn(null);
        var original=Map.copyOf(context.getContext());
        assertThrows(IqcException.class,()->controller.apiStats(null,null));
        verifyNoInteractions(queries);verify(organization,never()).schedulerAccess(anyString());
        assertEquals(original,context.getContext());assertSame(authentication,SecurityContextHolder.getContext().getAuthentication());
    }
}
